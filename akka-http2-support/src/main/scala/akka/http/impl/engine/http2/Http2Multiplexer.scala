/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import scala.collection.mutable
import akka.stream.stage.{ GraphStageLogic, InHandler, StageLogging }
import akka.util.ByteString

/**
 * INTERNAL API
 *
 * The internal interface Http2ServerDemux uses to drive the multiplexer.
 */
private[http2] trait Http2Multiplexer {
  def pushControlFrame(frame: FrameEvent): Unit
  def registerSubStream(sub: Http2SubStream): Unit
  def cancelSubStream(streamId: Int): Unit
  def updateWindow(streamId: Int, increment: Int): Unit
  def updateFrameSize(newFrameSize: Int): Unit
  def updateDefaultWindow(newDefaultWindow: Int): Unit
}
/**
 * INTERNAL API
 *
 * The current default multiplexer.
 */
private[http2] trait Http2MultiplexerSupport { logic: GraphStageLogic with StageLogging ⇒
  def createMultiplexer(outlet: GenericOutlet[FrameEvent]): Http2Multiplexer =
    new BufferedOutletExtended[FrameEvent](outlet) with Http2Multiplexer {
      class OutStream(
        var inlet:              Option[SubSinkInlet[ByteString]],
        var outboundWindowLeft: Int
      )

      private var currentInitialWindow = Http2Protocol.InitialWindowSize
      private var totalOutboundWindowLeft = Http2Protocol.InitialWindowSize

      private val outStreams = mutable.Map.empty[Int, OutStream]

      def pushControlFrame(frame: FrameEvent): Unit = push(frame)
      def registerSubStream(sub: Http2SubStream): Unit = {
        pushControlFrame(sub.initialHeaders)
        if (!sub.initialHeaders.endStream) { // if endStream is set, the source is never read
          val subIn = new SubSinkInlet[ByteString](s"substream-in-${sub.streamId}")
          streamFor(sub.streamId).inlet = Some(subIn)

          subIn.pull()
          subIn.setHandler(new InHandler {
            def onPush(): Unit = pushWithTrigger(DataFrame(sub.streamId, endStream = false, subIn.grab()), () ⇒
              if (!subIn.isClosed) subIn.pull())

            override def onUpstreamFinish(): Unit = pushControlFrame(DataFrame(sub.streamId, endStream = true, ByteString.empty))
          })
          sub.data.runWith(subIn.sink)(subFusingMaterializer)
        }
      }
      def updateWindow(streamId: Int, increment: Int): Unit = {
        if (streamId == 0) {
          totalOutboundWindowLeft += increment
          debug(s"Updating outgoing connection window by $increment to $totalOutboundWindowLeft")
        } else {
          updateWindowFor(streamId, increment)
          debug(s"Updating window for $streamId by $increment to ${windowLeftFor(streamId)} bufferedElements: ${buffer.size()}")
        }
        tryFlush()
      }

      def cancelSubStream(streamId: Int): Unit = outStreams(streamId).inlet.foreach(_.cancel())
      def updateFrameSize(newFrameSize: Int): Unit = {} // ignored so far
      def updateDefaultWindow(newDefaultWindow: Int): Unit = {
        val delta = newDefaultWindow - currentInitialWindow

        currentInitialWindow = newDefaultWindow
        outStreams.values.foreach(_.outboundWindowLeft += delta)
      }

      private def streamFor(streamId: Int): OutStream = outStreams.get(streamId) match {
        case None ⇒
          val newOne = new OutStream(None, currentInitialWindow)
          outStreams += streamId → newOne
          newOne
        case Some(old) ⇒ old
      }
      private def windowLeftFor(streamId: Int): Int = streamFor(streamId).outboundWindowLeft
      private def updateWindowFor(streamId: Int, increment: Int): Unit = streamFor(streamId).outboundWindowLeft += increment

      override def doPush(elem: ElementAndTrigger): Unit =
        elem.element match {
          case d @ DataFrame(streamId, _, pl) ⇒
            if (pl.size <= totalOutboundWindowLeft && pl.size <= windowLeftFor(streamId)) {
              super.doPush(elem)

              val size = pl.size
              totalOutboundWindowLeft -= size
              updateWindowFor(streamId, -size)

              debug(s"Pushed $size bytes of data for stream $streamId total window left: $totalOutboundWindowLeft per stream window left: ${windowLeftFor(streamId)}")
            } else {
              debug(s"Couldn't send because no window left. Size: ${pl.size} total: $totalOutboundWindowLeft per stream: ${windowLeftFor(streamId)}")
              // adding to end of the queue only works if there's only ever one frame per
              // substream in the queue (which is the case since backpressure was introduced)
              // TODO: we should try to find another stream to push data in this case
              buffer.add(elem)
            }
          case _ ⇒
            super.doPush(elem)
        }

      private def debug(msg: String): Unit = log.debug(msg)
    }
}
