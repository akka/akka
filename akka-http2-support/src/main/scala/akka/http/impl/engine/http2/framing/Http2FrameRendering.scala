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

    override def onPush(): Unit = {
      val frame = grab(frameIn)

      frame match {
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

    private def applySettings(s: immutable.Seq[Setting]): Unit = {
      s foreach {
        case _ ⇒ // FIXME handle acked settings here
      }
    }
  }

}
