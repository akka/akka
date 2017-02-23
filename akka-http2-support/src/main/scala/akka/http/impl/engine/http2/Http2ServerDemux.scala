/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.http2.Http2Compliance.Http2ProtocolException
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode.{ COMPRESSION_ERROR, FLOW_CONTROL_ERROR, FRAME_SIZE_ERROR }
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.impl.io.ByteStringParser.ParsingException
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, StageLogging }
import akka.util.ByteString

import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * This stage contains all control logic for handling frames and (de)muxing data to/from substreams.
 *
 * (This is not a final documentation, more like a brain-dump of how it could work.)
 *
 * The BidiStage consumes and produces FrameEvents from the network. It will output one Http2SubStream
 * for incoming frames per substream and likewise accepts a single Http2SubStream per substream with
 * outgoing frames.
 *
 * (An alternative API would just push a BidiHttp2SubStream(subStreamFlow: Flow[StreamFrameEvent, StreamFrameEvent])
 *  similarly to IncomingConnection. This would more accurately model the one-to-one relation between incoming and
 *  outgoing Http2Substream directions but wouldn't stack so nicely with other BidiFlows.)
 *
 * Backpressure logic:
 *
 *  * read all incoming frames without applying backpressure
 *    * this ensures that all "control" frames are read in a timely manner
 *    * though, make sure limits are not exceeded
 *      * max connection limit (which limits number of parallel requests)
 *      * window sizes for incoming data frames
 *    * that means we need to buffer incoming substream data until the user handler (consuming the source in the Http2SubStream)
 *      will read it
 *    * per-connection and per-stream window updates should reflect how much data was (not) yet passed
 *      into the user handler and therefore are the main backpressure mechanism towards the peer
 *  * for the outgoing frame side we need to decide which frames to send per incoming demand
 *    * control frames (settings, ping, acks, window updates etc.) -> responses to incoming frames
 *    * substream frames -> incoming frame data from substreams
 *    * to be able to make a decision some data must already be buffered for those two sources of incoming frames
 *
 * Demultiplexing:
 *  * distribute incoming frames to their respective targets:
 *    * control frames: handled internally, may generate outgoing control frames directly
 *    * incoming HEADERS frames: creates a new Http2SubStream including a SubSource that will receive all upcoming
 *      data frames
 *    * incoming data frames: buffered and pushed to the SubSource of the respective substream
 *
 * Multiplexing:
 *  * schedule incoming frames from multiple sources to be pushed onto the shared medium
 *    * control frames: as generated from the stage itself (should probably preferred over everything else)
 *    * Http2SubStream produced by the user handler: read and push initial frame ASAP
 *    * outgoing data frames for each of the substreams: will comprise the bulk of the data and is
 *      where any clever, prioritizing, etc. i.e. tbd later sending strategies will apply
 *
 * In the best case we could just flattenMerge the outgoing side (hoping for the best) but this will probably
 * not work because the sending decision relies on dynamic window size and settings information that will be
 * only available in this stage.
 */
class Http2ServerDemux extends GraphStage[BidiShape[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream]] {

  import Http2ServerDemux._

  val frameIn = Inlet[FrameEvent]("Demux.frameIn")
  val frameOut = Outlet[FrameEvent]("Demux.frameOut")

  val substreamOut = Outlet[Http2SubStream]("Demux.substreamOut")
  val substreamIn = Inlet[Http2SubStream]("Demux.substreamIn")

  override val shape =
    BidiShape(substreamIn, frameOut, frameIn, substreamOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with GenericOutletSupport with Http2MultiplexerSupport with StageLogging {
      logic ⇒

      final case class SubStream(
        streamId: Int,
        state:    StreamState,
        outlet:   Option[BufferedOutlet[ByteString]]
      )

      val multiplexer = createMultiplexer(frameOut, StreamPrioritizer.first())

      override def preStart(): Unit = {
        pull(frameIn)
        pull(substreamIn)

        multiplexer.pushControlFrame(SettingsFrame(Nil)) // server side connection preface
      }

      // we should not handle streams later than the GOAWAY told us about with lastStreamId
      private var closedAfter: Option[Int] = None
      private var incomingStreams = mutable.Map.empty[Int, SubStream]
      private var maxConcurrentStreams: Option[Int] = None
      /**
       * The "last peer-initiated stream that was or might be processed on the sending endpoint in this connection"
       * @see http://httpwg.org/specs/rfc7540.html#rfc.section.6.8
       */
      def lastStreamId: Int = {
        incomingStreams.keys.toList.sortBy(-_).headOption.getOrElse(0) // FIXME should be optimised
      }

      def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit = {
        // http://httpwg.org/specs/rfc7540.html#rfc.section.6.8
        val last = lastStreamId
        closedAfter = Some(last)
        val frame = GoAwayFrame(last, errorCode, ByteString(debug))
        multiplexer.pushControlFrame(frame)
        // FIXME: handle the connection closing according to the specification
      }

      setHandler(frameIn, new InHandler {

        def onPush(): Unit = {
          val in = grab(frameIn)
          in match {
            case WindowUpdateFrame(streamId, increment) ⇒ multiplexer.updateWindow(streamId, increment)

            case priorityInfo: PriorityFrame ⇒
              multiplexer.updatePriority(priorityInfo)

            case e: StreamFrameEvent if !Http2Compliance.isClientInitiatedStreamId(e.streamId) ⇒
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR, "Not a valid client initiated stream id! Was: " + e.streamId)

            case e: StreamFrameEvent if e.streamId > closedAfter.getOrElse(Int.MaxValue) ⇒
            // streams that will have a greater stream id than the one we sent with GOAWAY will be ignored

            case frame @ ParsedHeadersFrame(streamId, endStream, headers, prioInfo) if lastStreamId < streamId ⇒
              // TODO: process priority information
              val (data: Source[ByteString, NotUsed], outlet: Option[BufferedOutlet[ByteString]]) =
                if (endStream) (Source.empty, None)
                else {
                  val subSource = new SubSourceOutlet[ByteString](s"substream-out-$streamId")
                  (Source.fromGraph(subSource.source), Some(new BufferedOutlet[ByteString](subSource) {
                    override def onDownstreamFinish(): Unit =
                      // FIXME: when substream (= request entity) is cancelled, we need to RST_STREAM
                      // if the stream is finished and sent a RST_STREAM we can just remove the incoming stream from our map
                      incomingStreams.remove(streamId)
                  }))
                }
              val entry = SubStream(streamId, StreamState.Open /* FIXME stream state */ , outlet)
              incomingStreams += streamId → entry // TODO optimise for lookup later on

              dispatchSubstream(Http2SubStream(frame, data))

              prioInfo.foreach(multiplexer.updatePriority)

            case e: StreamFrameEvent if !incomingStreams.contains(e.streamId) ⇒
              // if a stream is invalid we will GO_AWAY
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR, "Unknown stream id: " + e.streamId)

            case h: ParsedHeadersFrame ⇒
              if (h.endStream)
                incomingStreams(h.streamId).outlet match {
                  case Some(outlet) ⇒ outlet.complete()
                  case None         ⇒ failSubstream(h.streamId, ErrorCode.STREAM_CLOSED, "Got HEADERS frame on closed stream")
                }
            // else just ignore intermediate HEADERS frames

            case DataFrame(streamId, endStream, payload) ⇒
              // technically this case is the same as StreamFrameEvent, however we're handling it earlier in the match here for efficiency
              // pushing http entity, TODO: handle flow control from here somehow?
              incomingStreams(streamId).outlet match {
                case Some(outlet) ⇒
                  outlet.push(payload)
                  if (endStream) outlet.complete()
                case None ⇒ failSubstream(streamId, ErrorCode.STREAM_CLOSED, "Got DATA frame on closed stream")
              }

            case RstStreamFrame(streamId, errorCode) ⇒
              // FIXME: also need to handle the other case when no response has been produced yet (inlet still None)
              multiplexer.cancelSubStream(streamId)

            case SettingsFrame(settings) ⇒
              if (settings.nonEmpty) debug(s"Got ${settings.length} settings!")

              var settingsAppliedOk = true

              settings.foreach {
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, value) ⇒
                  if (value >= 0) {
                    debug(s"Setting initial window to $value")
                    multiplexer.updateDefaultWindow(value)
                  } else {
                    pushGOAWAY(FLOW_CONTROL_ERROR, s"Invalid value for SETTINGS_INITIAL_WINDOW_SIZE: $value")
                    settingsAppliedOk = false
                  }
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) ⇒
                  multiplexer.updateMaxFrameSize(value)
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, value) ⇒
                  debug(s"Setting max concurrent streams to $value (not enforced)")
                  maxConcurrentStreams = Some(value)
                case Setting(id, value) ⇒
                  debug(s"Ignoring setting $id -> $value (in Demux)")
              }

              if (settingsAppliedOk) {
                multiplexer.pushControlFrame(SettingsAckFrame(settings))
              }

            case PingFrame(true, _) ⇒
            // ignore for now (we don't send any pings)
            case PingFrame(false, data) ⇒
              multiplexer.pushControlFrame(PingFrame(ack = true, data))

            case e ⇒
              debug(s"Got unhandled event $e")
            // ignore unknown frames
          }
          pull(frameIn)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          ex match {
            // every IllegalHttp2StreamIdException will be a GOAWAY with PROTOCOL_ERROR
            case e: Http2Compliance.IllegalHttp2StreamIdException ⇒
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR, e.getMessage)

            case e: Http2Compliance.Http2ProtocolException ⇒
              pushGOAWAY(e.errorCode, e.getMessage)

            case e: Http2Compliance.Http2ProtocolStreamException ⇒
              incomingStreams.remove(e.streamId)
              multiplexer.pushControlFrame(RstStreamFrame(e.streamId, e.errorCode))

            case e: ParsingException ⇒
              e.getCause match {
                case null  ⇒ super.onUpstreamFailure(e) // fail with the raw parsing exception
                case cause ⇒ onUpstreamFailure(cause) // unwrap the cause, which should carry ComplianceException and recurse 
              }

            // handle every unhandled exception
            case NonFatal(e) ⇒
              super.onUpstreamFailure(e)
          }
        }
      })

      val bufferedSubStreamOutput = new BufferedOutlet[Http2SubStream](substreamOut)
      def dispatchSubstream(sub: Http2SubStream): Unit = bufferedSubStreamOutput.push(sub)

      def failSubstream(streamId: Int, errorCode: ErrorCode, description: String): Unit = {
        log.debug(s"Substream $streamId failed with $errorCode: $description")
        multiplexer.pushControlFrame(RstStreamFrame(streamId, errorCode))
      }

      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          pull(substreamIn)
          multiplexer.registerSubStream(sub)
        }
      })

      def debug(msg: String): Unit = println(msg)
    }

}

object Http2ServerDemux {
  sealed trait StreamState
  object StreamState {
    case object Idle extends StreamState
    case object Open extends StreamState
    case object Closed extends StreamState
    case object HalfClosedLocal extends StreamState
    case object HalfClosedRemote extends StreamState

    // for PUSH_PROMISE
    // case object ReservedLocal extends StreamState
    // case object ReservedRemote extends StreamState
  }
}
