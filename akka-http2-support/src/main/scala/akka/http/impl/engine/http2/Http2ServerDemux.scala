/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStage
import akka.stream.stage.InHandler

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
    new GraphStageLogic(shape) with BufferedOutletSupport {
      logic ⇒

      final case class SubStream(
        streamId:                  Int,
        headers:                   HeadersFrame,
        state:                     StreamState,
        outlet:                    SubSourceOutlet[StreamFrameEvent],
        inlet:                     Option[SubSinkInlet[FrameEvent]],
        initialOutboundWindowLeft: Long
      ) extends BufferedOutlet[StreamFrameEvent](outlet) {
        var outboundWindowLeft = initialOutboundWindowLeft

        override def onDownstreamFinish(): Unit = {
          // FIXME: when substream (= request entity) is cancelled, we need to RST_STREAM

          // if the stream is finished and sent a RST_STREAM we can just remove the incoming stream from our map
          incomingStreams.remove(streamId)
          ()
        }
      }

      override def preStart(): Unit = {
        pull(frameIn)
        pull(substreamIn)

        bufferedFrameOut.push(SettingsFrame(Nil)) // server side connection preface
      }

      // we should not handle streams later than the GOAWAY told us about with lastStreamId
      private var closedAfter: Option[Int] = None
      private var incomingStreams = mutable.Map.empty[Int, SubStream]
      private var totalOutboundWindowLeft = Http2Protocol.InitialWindowSize
      private var streamLevelWindow = Http2Protocol.InitialWindowSize

      def lastStreamId: Int = {
        incomingStreams.lastOption.map(_._1).getOrElse(0)
      }

      def pushGOAWAY(errorCode: ErrorCode = Http2Protocol.ErrorCode.PROTOCOL_ERROR): Unit = {
        // http://httpwg.org/specs/rfc7540.html#rfc.section.6.8
        val last = lastStreamId
        closedAfter = Some(last)
        bufferedFrameOut.push(GoAwayFrame(last, errorCode))
        // FIXME: handle the connection closing according to the specification
      }

      setHandler(frameIn, new InHandler {
        def onPush(): Unit = {
          val in = grab(frameIn)
          in match {
            case WindowUpdateFrame(0, increment) ⇒
              totalOutboundWindowLeft += increment
              debug(f"outbound window is now $totalOutboundWindowLeft%10d after increment $increment%6d")
              bufferedFrameOut.tryFlush()

            case e: StreamFrameEvent if !Http2Compliance.isClientInitiatedStreamId(e.streamId) ⇒
              pushGOAWAY()

            case e: StreamFrameEvent if e.streamId > closedAfter.getOrElse(Int.MaxValue) ⇒
            // streams that will have a greater stream id than the one we sent with GO_AWAY will be ignored

            case headers @ HeadersFrame(streamId, endStream, endHeaders, fragment) if lastStreamId < streamId ⇒
              val subSource = new SubSourceOutlet[StreamFrameEvent](s"substream-out-$streamId")
              val handler = SubStream(streamId, headers, StreamState.Open /* FIXME stream state */ , subSource, None, streamLevelWindow)
              incomingStreams += streamId → handler // TODO optimise for lookup later on

              val sub = makeHttp2SubStream(handler)
              if (sub.initialFrame.endHeaders) dispatchSubstream(sub)
            // else we're expecting some continuation frames before we kick off the dispatch
            // FIXME: handle the correct order of frames

            case h: HeadersFrame ⇒
              pushGOAWAY()

            case e: StreamFrameEvent if !incomingStreams.contains(e.streamId) ⇒
              // if a stream is invalid we will GO_AWAY
              pushGOAWAY()

            case cont: ContinuationFrame ⇒
              // continue to build up headers (CONTINUATION come directly after HEADERS frame, and before DATA)
              val streamId = cont.streamId
              val streamHandler = incomingStreams(streamId)
              val updatedHandler = concatContinuationIntoHeaders(streamHandler, cont)
              val sub = makeHttp2SubStream(updatedHandler)
              if (updatedHandler.headers.endHeaders) dispatchSubstream(sub)

            case data: DataFrame ⇒
              // technically this case is the same as StreamFrameEvent, however we're handling it earlier in the match here for efficiency
              incomingStreams(data.streamId).push(data) // pushing http entity, handle flow control from here somehow?

            case RstStreamFrame(streamId, errorCode) ⇒
              // FIXME: also need to handle the other case when no response has been produced yet (inlet still None)
              incomingStreams(streamId).inlet.foreach(_.cancel())

            case WindowUpdateFrame(streamId, increment) ⇒
              incomingStreams(streamId).outboundWindowLeft += increment
              debug(f"outbound window for [$streamId%3d] is now ${incomingStreams(streamId).outboundWindowLeft}%10d after increment $increment%6d")
              bufferedFrameOut.tryFlush()

            case PriorityFrame(streamId, exclusiveFlag, streamDependency, weight) ⇒
              debug(s"Received PriorityFrame for stream $streamId with ${if (exclusiveFlag) "exclusive " else "non-exclusive "} dependency on stream $streamDependency and weight $weight")

            case e: StreamFrameEvent ⇒
              incomingStreams(e.streamId).push(e)

            case SettingsFrame(settings) ⇒
              debug(s"Got ${settings.length} settings!")
              settings.foreach {
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, value) ⇒
                  debug(s"Setting initial window to $value")
                  val delta = value - streamLevelWindow
                  streamLevelWindow = value
                  incomingStreams.values.foreach(_.outboundWindowLeft += delta)
                case Setting(id, value) ⇒ debug(s"Ignoring setting $id -> $value")
              }

              bufferedFrameOut.push(SettingsAckFrame)

            case PingFrame(true, _) ⇒ // ignore for now (we don't send any pings)
            case PingFrame(false, data) ⇒
              bufferedFrameOut.push(PingFrame(ack = true, data))

            case e ⇒
              println(s"Got unhandled event $e")
            // ignore unknown frames
          }
          pull(frameIn)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          ex match {
            // every IllegalHttp2StreamIdException will be a GOAWAY with PROTOCOL_ERROR
            case e: Http2Compliance.IllegalHttp2StreamIdException ⇒
              pushGOAWAY()

            // handle every unhandled exception
            case NonFatal(e) ⇒
              super.onUpstreamFailure(e)
          }
        }
      })

      val bufferedSubStreamOutput = new BufferedOutlet[Http2SubStream](substreamOut)

      def dispatchSubstream(sub: Http2SubStream): Unit = bufferedSubStreamOutput.push(sub)

      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          pull(substreamIn)
          bufferedFrameOut.push(sub.initialFrame)
          val subIn = new SubSinkInlet[FrameEvent](s"substream-in-${sub.streamId}")
          incomingStreams = incomingStreams.updated(sub.streamId, incomingStreams(sub.streamId).copy(inlet = Some(subIn)))
          subIn.pull()
          subIn.setHandler(new InHandler {
            def onPush(): Unit = bufferedFrameOut.pushWithTrigger(subIn.grab(), () ⇒
              if (!subIn.isClosed) subIn.pull())

            override def onUpstreamFinish(): Unit = () // FIXME: check for truncation (last frame must have endStream / endHeaders set)
          })
          sub.frames.runWith(subIn.sink)(subFusingMaterializer)
        }
      })

      val bufferedFrameOut = new BufferedOutletExtended[FrameEvent](frameOut) {
        override def doPush(elem: ElementAndTrigger): Unit = {
          elem.element match {
            case d @ DataFrame(streamId, _, pl) ⇒
              if (pl.size <= totalOutboundWindowLeft && pl.size <= incomingStreams(streamId).outboundWindowLeft) {
                super.doPush(elem)

                val size = pl.size
                totalOutboundWindowLeft -= size
                incomingStreams(streamId).outboundWindowLeft -= size

                debug(s"Pushed $size bytes of data for stream $streamId total window left: $totalOutboundWindowLeft per stream window left: ${incomingStreams(streamId).outboundWindowLeft}")
              } else {
                debug(s"Couldn't send because no window left. Size: ${pl.size} total: $totalOutboundWindowLeft per stream: ${incomingStreams(streamId).outboundWindowLeft}")
                // adding to end of the queue only works if there's only ever one frame per
                // substream in the queue (which is the case since backpressure was introduced)
                // TODO: we should try to find another stream to push data in this case
                buffer.add(elem)
              }
            case _ ⇒
              super.doPush(elem)
          }
        }
      }

      private def concatContinuationIntoHeaders(handler: SubStream, cont: ContinuationFrame) = {
        val concatHeaderBlockFragment = handler.headers.headerBlockFragment ++ cont.payload
        val moreCompleteHeadersFrame = HeadersFrame(handler.streamId, cont.endHeaders, cont.endHeaders, concatHeaderBlockFragment)
        // update the SubStream we keep around
        val moreCompleteHandler = handler.copy(headers = moreCompleteHeadersFrame)
        incomingStreams += handler.streamId → moreCompleteHandler
        moreCompleteHandler
      }

      def makeHttp2SubStream(handler: SubStream): Http2SubStream = {
        val headers = handler.headers
        val subStream = handler.outlet
        if (headers.endStream && headers.endHeaders) {
          Http2SubStream(headers, Source.empty)
        } else {
          // FIXME a bit naive but correct I think -- todo check the spec
          val remainingFrames = Source.fromGraph(subStream.source)
            .collect({
              case d: DataFrame ⇒ d
              case f            ⇒ throw new Exception("Unexpected frame type! We thought only DataFrames are accepted from here on. Was: " + f)
            })
          Http2SubStream(headers, remainingFrames)
        }
      }

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
