/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.http.impl.engine.ws.FrameHandler.{ UserHandlerErredOut, _ }
import akka.http.impl.engine.ws.WebSocket.Tick
import akka.http.impl.util.Timestamp
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import scala.concurrent.duration.FiniteDuration

/**
 * Implements the transport connection close handling at the end of the pipeline.
 *
 * INTERNAL API
 */
private[http] class FrameOutHandler(serverSide: Boolean, _closeTimeout: FiniteDuration, log: LoggingAdapter)
  extends GraphStage[FlowShape[FrameOutHandler.Input, FrameStart]] {
  val in = Inlet[FrameOutHandler.Input]("FrameOutHandler.in")
  val out = Outlet[FrameStart]("FrameOutHandler.out")

  override def shape = FlowShape(in, out)

  private def closeTimeout: Timestamp = Timestamp.now + _closeTimeout

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    private def absorbTermination() =
      if (isAvailable(out)) getHandler(out).onPull()

    private object Idle extends InHandler with ProcotolExceptionHandling {
      override def onPush() =
        grab(in) match {
          case start: FrameStart   ⇒ push(out, start)
          case DirectAnswer(frame) ⇒ push(out, frame)
          case PeerClosed(code, reason) if !code.exists(Protocol.CloseCodes.isError) ⇒
            // let user complete it, FIXME: maybe make configurable? immediately, or timeout
            setHandler(in, new WaitingForUserHandlerClosed(FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)))
            pull(in)
          case PeerClosed(code, reason) ⇒
            val closeFrame = FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)
            if (serverSide) {
              push(out, closeFrame)
              completeStage()
            } else {
              setHandler(in, new WaitingForTransportClose)
              push(out, closeFrame)
            }
          case ActivelyCloseWithCode(code, reason) ⇒
            val closeFrame = FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)
            setHandler(in, new WaitingForPeerCloseFrame())
            push(out, closeFrame)
          case UserHandlerCompleted ⇒
            setHandler(in, new WaitingForPeerCloseFrame())
            push(out, FrameEvent.closeFrame(Protocol.CloseCodes.Regular))
          case UserHandlerErredOut(e) ⇒
            log.error(e, s"Websocket handler failed with ${e.getMessage}")
            setHandler(in, new WaitingForPeerCloseFrame())
            push(out, FrameEvent.closeFrame(Protocol.CloseCodes.UnexpectedCondition, "internal error"))
          case Tick ⇒ pull(in) // ignore
        }

      override def onUpstreamFinish(): Unit = {
        becomeSendOutCloseFrameAndComplete(FrameEvent.closeFrame(Protocol.CloseCodes.Regular))
        absorbTermination()
      }
    }

    /**
     * peer has closed, we want to wait for user handler to close as well
     */
    private class WaitingForUserHandlerClosed(closeFrame: FrameStart) extends InHandler {
      def onPush() =
        grab(in) match {
          case UserHandlerCompleted ⇒ sendOutLastFrame()
          case UserHandlerErredOut(e) ⇒
            log.error(e, s"Websocket handler failed while waiting for handler completion with ${e.getMessage}")
            sendOutLastFrame()
          case start: FrameStart ⇒ push(out, start)
          case _                 ⇒ pull(in) // ignore
        }

      private def sendOutLastFrame(): Unit =
        if (serverSide) {
          push(out, closeFrame)
          completeStage()
        } else {
          setHandler(in, new WaitingForTransportClose())
          push(out, closeFrame)
        }

      override def onUpstreamFinish(): Unit =
        fail(out, new IllegalStateException("Mustn't complete before user has completed"))
    }

    /**
     * we have sent out close frame and wait for peer to sent its close frame
     */
    private class WaitingForPeerCloseFrame(timeout: Timestamp = closeTimeout) extends InHandler with ProcotolExceptionHandling {
      override def onPush() =
        grab(in) match {
          case Tick ⇒
            if (timeout.isPast) completeStage()
            else pull(in)
          case PeerClosed(code, reason) ⇒
            if (serverSide) completeStage()
            else {
              setHandler(in, new WaitingForTransportClose())
              pull(in)
            }
          case _ ⇒ pull(in) // ignore
        }
    }

    /**
     * Both side have sent their close frames, server should close the connection first
     */
    private class WaitingForTransportClose(timeout: Timestamp = closeTimeout) extends InHandler with ProcotolExceptionHandling {
      override def onPush() = {
        grab(in) match {
          case Tick ⇒
            if (timeout.isPast) completeStage()
            else pull(in)
          case _ ⇒ pull(in) // ignore
        }
      }
    }

    /** If upstream has already failed we just wait to be able to deliver our close frame and complete */
    private class SendOutCloseFrameAndComplete(closeFrame: FrameStart) extends InHandler with OutHandler with ProcotolExceptionHandling {
      override def onPush() =
        fail(out, new IllegalStateException("Didn't expect push after completion"))

      override def onPull(): Unit = {
        push(out, closeFrame)
        completeStage()
      }

      override def onUpstreamFinish(): Unit =
        absorbTermination()
    }

    def becomeSendOutCloseFrameAndComplete(frameStart: FrameStart): Unit = {
      val inNOutHandler = new SendOutCloseFrameAndComplete(frameStart)
      setHandler(in, inNOutHandler)
      setHandler(out, inNOutHandler)
    }

    /** We handle [[ProtocolException]] in a special way (by terminating with a ProtocolError) */
    private trait ProcotolExceptionHandling extends InHandler {
      override def onUpstreamFailure(cause: Throwable): Unit =
        cause match {
          case p: ProtocolException ⇒
            becomeSendOutCloseFrameAndComplete(FrameEvent.closeFrame(Protocol.CloseCodes.ProtocolError))
            absorbTermination()
          case _ ⇒ super.onUpstreamFailure(cause)
        }
    }

    // init handlers

    setHandler(in, Idle)
    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

  }
}

private[http] object FrameOutHandler {
  type Input = AnyRef

  def create(serverSide: Boolean, closeTimeout: FiniteDuration, log: LoggingAdapter): Flow[Input, FrameStart, NotUsed] =
    Flow[Input].via(new FrameOutHandler(serverSide, closeTimeout, log)).named("frameOutHandler")
}