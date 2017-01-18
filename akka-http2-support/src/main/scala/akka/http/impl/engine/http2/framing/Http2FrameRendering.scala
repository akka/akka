/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.framing

import akka.event.Logging
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.http.impl.engine.http2._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString

import scala.collection.immutable

/** INTERNAL API */
private[http] class Http2FrameRendering extends GraphStage[FlowShape[FrameEvent, ByteString]] {

  val frameIn = Inlet[FrameEvent]("Http2FrameRendering.frameIn")
  val netOut = Outlet[ByteString]("Http2FrameRendering.netOut")

  override def initialAttributes = Attributes.name(Logging.simpleName(getClass))

  override val shape = FlowShape[FrameEvent, ByteString](frameIn, netOut)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    setHandlers(frameIn, netOut, this)

    private var _outMaxFrameSize: Int = Http2Protocol.InitialMaxFrameSize // default

    final def maxOutFrameSize: Int = _outMaxFrameSize

    final def updateMaxOutFrameSize(value: Int): Unit = {
      Http2Compliance.validateMaxFrameSize(value)
      _outMaxFrameSize = value
    }

    override def onPush(): Unit = {
      val frame = grab(frameIn)
      frame match {
        case d @ DataFrame(_, _, payload) if payload.size > maxOutFrameSize ⇒
          // payload too large, we must split it into multiple frames:
          val splitDataFrames = splitByPayloadSize(d, maxOutFrameSize).iterator.map(FrameRenderer.render)
          emitMultiple(netOut, splitDataFrames) // TODO optimise if fits in 1 frame

        case ack @ SettingsAckFrame(s) ⇒
          applySettings(s)
          push(netOut, FrameRenderer.render(ack))

        case _ ⇒
          // normal frame, just render it:
          val rendered = FrameRenderer.render(frame)
          // Http2Compliance.requireFrameSizeLessOrEqualThan(rendered.length, settings.maxOutFrameSize, hint = s"Frame type was ${Logging.simpleName(frame)}")
          push(netOut, rendered)
      }
    }

    override def onPull(): Unit = pull(frameIn)

    private def splitByPayloadSize(d: DataFrame, size: Int): immutable.Seq[DataFrame] = {
      debug(s"Splitting up too large data-frame into smaller frames due to exceeding max frame size. Length: ${d.payload.length}, max: ${size}")
      val parts = d.payload.grouped(size) // TODO optimise, splitAt would be better

      if (d.endStream) {
        if (d.payload.nonEmpty) throw new IllegalStateException("DataFrame marked endStream should have empty payload!") // TODO at least in current impl

        // only the last of the split-up frames must close the stream
        val all = parts.map(p ⇒ d.copy(endStream = false, payload = p)).toVector
        // TODO not optimal impl, optimise later:
        all.dropRight(1) ++ Vector(all.last.copy(endStream = true))
      } else {
        parts.map(p ⇒ d.copy(payload = p)).toVector
      }
    }

    private def applySettings(s: immutable.Seq[Setting]): Unit = {
      s foreach {
        case Setting(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) ⇒
          updateMaxOutFrameSize(value)
          debug(s"Set max outgoing frame size to: ${value}")
        case _ ⇒ // ignore other settings, not applicable for this stage
      }
    }
  }

  private def debug(s: String): Unit = println(s)

}
