/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.stream.Attributes

import scala.collection.mutable
import scala.collection.immutable
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler, StageLogging }
import akka.util.{ ByteString, OptionVal }

/**
 * INTERNAL API
 *
 * The internal interface Http2ServerDemux uses to drive the multiplexer.
 */
@InternalApi
private[http2] trait Http2Multiplexer {
  def pushControlFrame(frame: FrameEvent): Unit
  def registerSubStream(sub: Http2SubStream): Unit

  /** Notifies the multiplexer that the peer decided to cancel the substream */
  def cancelSubStream(streamId: Int): Unit
  def updateWindow(streamId: Int, increment: Int): Unit
  def updateMaxFrameSize(newMaxFrameSize: Int): Unit
  def updateDefaultWindow(newDefaultWindow: Int): Unit
  def updatePriority(priorityFrame: PriorityFrame): Unit

  def reportTimings(): Unit
}

/**
 * INTERNAL API
 *
 * The current default multiplexer.
 */
@InternalApi
private[http2] trait Http2MultiplexerSupport { logic: GraphStageLogic with StageLogging ⇒
  def createMultiplexer(outlet: GenericOutlet[FrameEvent], prioritizer: StreamPrioritizer): Http2Multiplexer =
    new Http2Multiplexer with OutHandler with StateTimingSupport with LogSupport {
      outlet.setHandler(this)

      class OutStream(
        val streamId:           Int,
        private var maybeInlet: Option[SubSinkInlet[ByteString]],
        var outboundWindowLeft: Int,
        private var buffer:     ByteString                       = ByteString.empty,
        var upstreamClosed:     Boolean                          = false,
        var endStreamSent:      Boolean                          = false
      ) extends InHandler {
        private def inlet: SubSinkInlet[ByteString] = maybeInlet.get
        def canSend = (buffer.nonEmpty && outboundWindowLeft > 0) || (upstreamClosed && !endStreamSent)

        def shouldSendEndStreamNow: Boolean = upstreamClosed && !endStreamSent && buffer.isEmpty

        def registerIncomingData(inlet: SubSinkInlet[ByteString]): Unit = {
          require(!maybeInlet.isDefined)

          this.maybeInlet = Some(inlet)
          inlet.pull()
          inlet.setHandler(this)
        }

        def nextFrame(maxBytesToSend: Int): DataFrame = {
          val toTake = maxBytesToSend min buffer.size min outboundWindowLeft
          val toSend = buffer.take(toTake)
          require(toSend.nonEmpty)

          outboundWindowLeft -= toTake
          buffer = buffer.drop(toTake)

          val endStream = shouldSendEndStreamNow

          maybePull()

          debug(s"[$streamId] sending ${toSend.size} bytes, endStream = $endStream")

          endStreamSent = endStream
          if (endStream) closeStream()

          DataFrame(streamId, endStream, toSend)
        }

        private def maybePull(): Unit = {
          // TODO: Check that buffer is not too much over the limit (which we might warn the user about)
          //       The problem here is that backpressure will only work properly if batch elements like
          //       ByteString have a reasonable size.
          if (buffer.size < maxBytesToBufferPerSubstream && !inlet.hasBeenPulled && !inlet.isClosed) inlet.pull()
        }

        /** Closes the stream completely */
        def closeStream(): Unit = {
          upstreamClosed = true
          endStreamSent = true
          buffer = ByteString.empty
          maybeInlet.foreach(_.cancel())
          maybeInlet = None
          outStreams.remove(streamId)
        }

        def cancelStream(): Unit = closeStream()
        def bufferedBytes: Int = buffer.size

        override def onPush(): Unit = {
          val newData = inlet.grab()
          buffer ++= newData

          debug(s"[$streamId] buffered ${buffer.size} bytes")
          maybePull()

          // else wait for more data being drained
          if (canSend) enqueueOutStream(this)
        }

        override def onUpstreamFinish(): Unit =
          if (buffer.isEmpty) {
            // push last frame immediately
            closeStream()
            pushControlFrame(DataFrame(streamId, endStream = true, ByteString.empty))
          } else
            upstreamClosed = true

        override def onUpstreamFailure(ex: Throwable): Unit = {
          log.error(ex, s"Substream $streamId failed with $ex")
          closeStream() // RST_STREAM closes the stream
          pushControlFrame(RstStreamFrame(streamId, Http2Protocol.ErrorCode.INTERNAL_ERROR))
        }
      }

      private var currentInitialWindow = Http2Protocol.InitialWindowSize
      private var currentMaxFrameSize = Http2Protocol.InitialMaxFrameSize
      private var connectionWindowLeft = Http2Protocol.InitialWindowSize

      private val outStreams = mutable.Map.empty[Int, OutStream]

      override def pushControlFrame(frame: FrameEvent): Unit = state.pushControlFrame(frame)

      override def registerSubStream(sub: Http2SubStream): Unit = {
        pushControlFrame(sub.initialHeaders)
        sub.initialHeaders.priorityInfo.foreach(updatePriority)
        if (!sub.initialHeaders.endStream) { // if endStream is set, the source is never read
          val subIn = new SubSinkInlet[ByteString](s"substream-in-${sub.streamId}")
          val info = streamFor(sub.streamId)
          info.registerIncomingData(subIn)
          sub.data.runWith(subIn.sink)(subFusingMaterializer)
        }
      }

      override def updateWindow(streamId: Int, increment: Int): Unit =
        if (streamId == 0) {
          connectionWindowLeft += increment
          debug(s"Updating outgoing connection window by $increment to $connectionWindowLeft")
          state.connectionWindowAvailable()
        } else {
          updateWindowFor(streamId, increment)
          debug(s"Updating window for $streamId by $increment to ${windowLeftFor(streamId)} buffered bytes: ${streamFor(streamId).bufferedBytes}")
        }

      override def cancelSubStream(streamId: Int): Unit = streamFor(streamId).cancelStream()
      override def updateMaxFrameSize(newMaxFrameSize: Int): Unit = currentMaxFrameSize = newMaxFrameSize
      override def updateDefaultWindow(newDefaultWindow: Int): Unit = {
        val delta = newDefaultWindow - currentInitialWindow

        currentInitialWindow = newDefaultWindow
        outStreams.values.foreach(i ⇒ updateWindowFor(i.streamId, delta))
      }
      override def updatePriority(info: PriorityFrame): Unit = prioritizer.updatePriority(info)

      private def streamFor(streamId: Int): OutStream = outStreams.get(streamId) match {
        case None ⇒
          val newOne = new OutStream(streamId, None, currentInitialWindow)
          outStreams += streamId → newOne
          newOne
        case Some(old) ⇒ old
      }
      private def windowLeftFor(streamId: Int): Int = streamFor(streamId).outboundWindowLeft
      private def updateWindowFor(streamId: Int, increment: Int): Unit = {
        val info = streamFor(streamId)
        info.outboundWindowLeft += increment
        if (info.canSend) enqueueOutStream(info)
      }

      def enqueueOutStream(outStream: OutStream): Unit = state.enqueueOutStream(outStream)

      override def onDownstreamFinish(): Unit = {
        outStreams.values.foreach(_.cancelStream())
        completeStage()
      }

      var state: MultiplexerState = Idle
      def onPull(): Unit = state.onPull()
      private def become(nextState: MultiplexerState): Unit = {
        if (nextState.name != state.name) recordStateChange(state.name, nextState.name)

        state = nextState
      }

      sealed trait MultiplexerState extends Product {
        def name: String = productPrefix

        def onPull(): Unit
        def pushControlFrame(frame: FrameEvent): Unit
        def connectionWindowAvailable(): Unit
        def enqueueOutStream(outStream: OutStream): Unit
      }

      // Multiplexer state machine
      // Idle: No data to send, no demand from the network (i.e. we were not yet pulled)
      // WaitingForData: Got demand from the network but no data to send
      // WaitingForNetworkToSendControlFrames: Control frames (and maybe data frames) are queued but there is no network demand
      // WaitingForNetworkToSendData: Data frames queued but no network demand
      // WaitingForConnectionWindow: Data frames queued, demand from the network, but no connection-level window available

      case object Idle extends MultiplexerState {
        def onPull(): Unit = become(WaitingForData)
        def pushControlFrame(frame: FrameEvent): Unit = become(WaitingForNetworkToSendControlFrames(frame :: Nil, immutable.TreeSet.empty))
        def connectionWindowAvailable(): Unit = ()
        def enqueueOutStream(outStream: OutStream): Unit = become(WaitingForNetworkToSendData(immutable.TreeSet(outStream.streamId)))
      }

      case object WaitingForData extends MultiplexerState {
        def onPull(): Unit = throw new IllegalStateException(s"pull unexpected while waiting for data")
        def pushControlFrame(frame: FrameEvent): Unit = {
          outlet.push(frame)
          become(Idle)
        }
        def connectionWindowAvailable(): Unit = () // nothing to do, as there is no data to send
        def enqueueOutStream(outStream: OutStream): Unit =
          if (connectionWindowLeft == 0) become(WaitingForConnectionWindow(immutable.TreeSet(outStream.streamId)))
          else {
            require(outStream.canSend)

            val maxBytesToSend = currentMaxFrameSize min connectionWindowLeft
            val frame = outStream.nextFrame(maxBytesToSend)
            outlet.push(frame)
            connectionWindowLeft -= frame.payload.size

            if (outStream.canSend) // if we still can send, then do
              become(WaitingForNetworkToSendData(immutable.TreeSet(outStream.streamId)))
            else
              become(Idle) // everything sent for now
          }
      }

      /** Not yet pulled but data waiting to be sent */
      case class WaitingForNetworkToSendControlFrames(controlFrameBuffer: immutable.Seq[FrameEvent], sendableOutstreams: immutable.Set[Int]) extends MultiplexerState {
        require(controlFrameBuffer.nonEmpty)
        def onPull(): Unit = controlFrameBuffer match {
          case first +: remaining ⇒
            outlet.push(first)
            become {
              if (remaining.isEmpty && sendableOutstreams.isEmpty) Idle
              else if (remaining.isEmpty) WaitingForNetworkToSendData(sendableOutstreams)
              else copy(remaining, sendableOutstreams)
            }
        }
        def pushControlFrame(frame: FrameEvent): Unit = become(copy(controlFrameBuffer = controlFrameBuffer :+ frame))
        def connectionWindowAvailable(): Unit = ()
        def enqueueOutStream(outStream: OutStream): Unit =
          if (!sendableOutstreams.contains(outStream.streamId))
            become(copy(sendableOutstreams = sendableOutstreams + outStream.streamId))
      }

      abstract class WithSendableOutStreams extends MultiplexerState {
        def sendableOutstreams: immutable.Set[Int]

        protected def sendNext(): Unit = {
          val chosenId = prioritizer.chooseSubstream(sendableOutstreams)
          val outStream = streamFor(chosenId)
          require(outStream.canSend)

          val maxBytesToSend = currentMaxFrameSize min connectionWindowLeft
          val frame = outStream.nextFrame(maxBytesToSend)
          outlet.push(frame)
          connectionWindowLeft -= frame.payload.size

          if (outStream.canSend) // if we still can send, then do
            become(WaitingForNetworkToSendData(sendableOutstreams))
          else if (sendableOutstreams.size == 1)
            // now empty
            become(Idle)
          else
            become(WaitingForNetworkToSendData(sendableOutstreams.filterNot(_ == chosenId))) // TODO: use Set instead
        }
      }

      case class WaitingForNetworkToSendData(sendableOutstreams: immutable.Set[Int]) extends WithSendableOutStreams {
        require(sendableOutstreams.nonEmpty)
        def onPull(): Unit =
          if (connectionWindowLeft > 0) sendNext()
          else // do nothing and wait for window first
            become(WaitingForConnectionWindow(sendableOutstreams))

        def pushControlFrame(frame: FrameEvent): Unit = become(WaitingForNetworkToSendControlFrames(frame :: Nil, sendableOutstreams))
        def connectionWindowAvailable(): Unit = ()
        def enqueueOutStream(outStream: OutStream): Unit =
          if (!sendableOutstreams.contains(outStream.streamId))
            become(copy(sendableOutstreams = sendableOutstreams + outStream.streamId))
      }

      /** Pulled and data is pending but no connection-level window available */
      case class WaitingForConnectionWindow(sendableOutstreams: immutable.Set[Int]) extends WithSendableOutStreams {
        require(sendableOutstreams.nonEmpty)
        def onPull(): Unit = throw new IllegalStateException(s"pull unexpected while waiting for connection window")
        def pushControlFrame(frame: FrameEvent): Unit = {
          outlet.push(frame)
          become(WaitingForNetworkToSendData(sendableOutstreams))
        }
        def connectionWindowAvailable(): Unit = sendNext()
        def enqueueOutStream(outStream: OutStream): Unit =
          if (!sendableOutstreams.contains(outStream.streamId))
            become(copy(sendableOutstreams = sendableOutstreams + outStream.streamId))
      }

      private def maxBytesToBufferPerSubstream = 2 * currentMaxFrameSize // for now, let's buffer two frames per substream

      def debug(msg: ⇒ String): Unit = log.debug(msg)
    }

  private trait LogSupport {
    def debug(msg: ⇒ String): Unit
  }

  private trait StateTimingSupport { self: LogSupport ⇒
    var timings = Map.empty[String, Long].withDefaultValue(0L)
    var lastTimestamp = System.nanoTime()

    def recordStateChange(oldState: String, newState: String): Unit = {
      val now = System.nanoTime()
      val lasted = now - lastTimestamp
      val name = oldState
      timings = timings.updated(name, timings(name) + lasted)
      lastTimestamp = now
      debug(s"Changing state from $oldState to $newState")
    }

    /** Logs DEBUG level timing data for the output side of the multiplexer*/
    def reportTimings(): Unit = {
      val timingsReport = timings.toSeq.sortBy(_._1).map {
        case (name, nanos) ⇒ f"${nanos / 1000000}%5d ms $name"
      }.mkString("\n")
      debug(s"Timing data for connection\n$timingsReport")
    }
  }
}
