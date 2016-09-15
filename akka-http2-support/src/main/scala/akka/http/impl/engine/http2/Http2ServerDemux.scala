/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler

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
    new GraphStageLogic(shape) with BufferedOutletSupport { logic ⇒
      case class SubStream(
        streamId: Int,
        state:    StreamState,
        outlet:   SubSourceOutlet[StreamFrameEvent]
      ) extends BufferedOutlet[StreamFrameEvent](outlet)

      override def preStart(): Unit = {
        pull(frameIn)
        pull(substreamIn)
      }

      var incomingStreams = Map.empty[Int, SubStream]

      setHandler(frameIn, new InHandler {
        def onPush(): Unit = {
          grab(frameIn) match {
            case headers @ HeadersFrame(streamId, endStream, endHeaders, fragment) ⇒
              val subSource = new SubSourceOutlet[StreamFrameEvent](s"substream-out-$streamId")
              val handler = SubStream(streamId, StreamState.Open /* FIXME */ , subSource)
              incomingStreams += streamId → handler

              val sub =
                if (endStream && endHeaders) Http2SubStream(headers, Source.empty)
                else Http2SubStream(headers, Source.fromGraph(subSource.source))

              dispatchSubstream(sub)

            case e: StreamFrameEvent if e.streamId > 0 ⇒ incomingStreams(e.streamId).push(e)

            case SettingsFrame(_) ⇒
              // FIXME: do something

              bufferedFrameOut.push(SettingsAckFrame)
            case e ⇒
              println(s"Got unknown event $e")
            // ignore unknown frames
          }
          pull(frameIn)
        }
      })

      val bufferedSubStreamOutput = new BufferedOutlet[Http2SubStream](substreamOut)
      def dispatchSubstream(sub: Http2SubStream): Unit = bufferedSubStreamOutput.push(sub)

      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          bufferedFrameOut.push(sub.initialFrame)
          val subIn = new SubSinkInlet[FrameEvent](s"substream-in-${sub.streamId}")
          subIn.pull()
          subIn.setHandler(new InHandler {
            def onPush(): Unit = {
              bufferedFrameOut.push(subIn.grab())
              subIn.pull() // FIXME: this is too greedy, we should wait until the next one is sent out
            }
          })
          sub.frames.runWith(subIn.sink)(subFusingMaterializer)
        }
      })

      val bufferedFrameOut = new BufferedOutlet[FrameEvent](frameOut)
      bufferedFrameOut.push(SettingsFrame(Nil)) // server side connection preface
    }
}

trait BufferedOutletSupport { logic: GraphStageLogic ⇒
  trait GenericOutlet[T] {
    def setHandler(handler: OutHandler): Unit
    def push(elem: T): Unit
  }
  object GenericOutlet {
    implicit def fromSubSourceOutlet[T](subSourceOutlet: SubSourceOutlet[T]): GenericOutlet[T] =
      new GenericOutlet[T] {
        def setHandler(handler: OutHandler): Unit = subSourceOutlet.setHandler(handler)
        def push(elem: T): Unit = subSourceOutlet.push(elem)
      }
    implicit def fromOutlet[T](outlet: Outlet[T]): GenericOutlet[T] =
      new GenericOutlet[T] {
        def setHandler(handler: OutHandler): Unit = logic.setHandler(outlet, handler)
        def push(elem: T): Unit = logic.emit(outlet, elem)
      }
  }
  class BufferedOutlet[T](outlet: GenericOutlet[T]) {
    val buffer: java.util.ArrayDeque[T] = new java.util.ArrayDeque[T]
    var pulled: Boolean = false

    outlet.setHandler(new OutHandler {
      def onPull(): Unit =
        if (!buffer.isEmpty) outlet.push(buffer.pop())
        else pulled = true
    })

    def push(elem: T): Unit =
      if (pulled) {
        outlet.push(elem)
        pulled = false
      } else buffer.push(elem)
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