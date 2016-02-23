/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Flow
import scala.concurrent.duration.FiniteDuration
import akka.stream.stage._
import akka.http.impl.util.Timestamp
import akka.http.impl.engine.ws.FrameHandler._
import WebSocket.Tick
import akka.http.impl.engine.ws.FrameHandler.UserHandlerErredOut

/**
 * Implements the transport connection close handling at the end of the pipeline.
 *
 * INTERNAL API
 */
private[http] class FrameOutHandler(serverSide: Boolean, _closeTimeout: FiniteDuration, log: LoggingAdapter) extends StatefulStage[FrameOutHandler.Input, FrameStart] {
  def initial: StageState[AnyRef, FrameStart] = Idle
  def closeTimeout: Timestamp = Timestamp.now + _closeTimeout

  private object Idle extends CompletionHandlingState {
    def onPush(elem: AnyRef, ctx: Context[FrameStart]): SyncDirective = elem match {
      case start: FrameStart   ⇒ ctx.push(start)
      case DirectAnswer(frame) ⇒ ctx.push(frame)
      case PeerClosed(code, reason) if !code.exists(Protocol.CloseCodes.isError) ⇒
        // let user complete it, FIXME: maybe make configurable? immediately, or timeout
        become(new WaitingForUserHandlerClosed(FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)))
        ctx.pull()
      case PeerClosed(code, reason) ⇒
        val closeFrame = FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)
        if (serverSide) ctx.pushAndFinish(closeFrame)
        else {
          become(new WaitingForTransportClose)
          ctx.push(closeFrame)
        }
      case ActivelyCloseWithCode(code, reason) ⇒
        val closeFrame = FrameEvent.closeFrame(code.getOrElse(Protocol.CloseCodes.Regular), reason)
        become(new WaitingForPeerCloseFrame())
        ctx.push(closeFrame)
      case UserHandlerCompleted ⇒
        become(new WaitingForPeerCloseFrame())
        ctx.push(FrameEvent.closeFrame(Protocol.CloseCodes.Regular))
      case UserHandlerErredOut(e) ⇒
        log.error(e, s"WebSocket handler failed with ${e.getMessage}")
        become(new WaitingForPeerCloseFrame())
        ctx.push(FrameEvent.closeFrame(Protocol.CloseCodes.UnexpectedCondition, "internal error"))
      case Tick ⇒ ctx.pull() // ignore
    }

    def onComplete(ctx: Context[FrameStart]): TerminationDirective = {
      become(new SendOutCloseFrameAndComplete(FrameEvent.closeFrame(Protocol.CloseCodes.Regular)))
      ctx.absorbTermination()
    }
  }

  /**
   * peer has closed, we want to wait for user handler to close as well
   */
  private class WaitingForUserHandlerClosed(closeFrame: FrameStart) extends CompletionHandlingState {
    def onPush(elem: AnyRef, ctx: Context[FrameStart]): SyncDirective = elem match {
      case UserHandlerCompleted ⇒ sendOutLastFrame(ctx)
      case UserHandlerErredOut(e) ⇒
        log.error(e, s"WebSocket handler failed while waiting for handler completion with ${e.getMessage}")
        sendOutLastFrame(ctx)
      case start: FrameStart ⇒ ctx.push(start)
      case _                 ⇒ ctx.pull() // ignore
    }

    def sendOutLastFrame(ctx: Context[FrameStart]): SyncDirective =
      if (serverSide) ctx.pushAndFinish(closeFrame)
      else {
        become(new WaitingForTransportClose())
        ctx.push(closeFrame)
      }

    def onComplete(ctx: Context[FrameStart]): TerminationDirective =
      ctx.fail(new IllegalStateException("Mustn't complete before user has completed"))
  }

  /**
   * we have sent out close frame and wait for peer to sent its close frame
   */
  private class WaitingForPeerCloseFrame(timeout: Timestamp = closeTimeout) extends CompletionHandlingState {
    def onPush(elem: AnyRef, ctx: Context[FrameStart]): SyncDirective = elem match {
      case Tick ⇒
        if (timeout.isPast) ctx.finish()
        else ctx.pull()
      case PeerClosed(code, reason) ⇒
        if (serverSide) ctx.finish()
        else {
          become(new WaitingForTransportClose())
          ctx.pull()
        }
      case _ ⇒ ctx.pull() // ignore
    }

    def onComplete(ctx: Context[FrameStart]): TerminationDirective = ctx.finish()
  }

  /**
   * Both side have sent their close frames, server should close the connection first
   */
  private class WaitingForTransportClose(timeout: Timestamp = closeTimeout) extends CompletionHandlingState {
    def onPush(elem: AnyRef, ctx: Context[FrameStart]): SyncDirective = elem match {
      case Tick ⇒
        if (timeout.isPast) ctx.finish()
        else ctx.pull()
      case _ ⇒ ctx.pull() // ignore
    }

    def onComplete(ctx: Context[FrameStart]): TerminationDirective = ctx.finish()
  }

  /** If upstream has already failed we just wait to be able to deliver our close frame and complete */
  private class SendOutCloseFrameAndComplete(closeFrame: FrameStart) extends CompletionHandlingState {
    def onPush(elem: AnyRef, ctx: Context[FrameStart]): SyncDirective =
      ctx.fail(new IllegalStateException("Didn't expect push after completion"))

    override def onPull(ctx: Context[FrameStart]): SyncDirective =
      ctx.pushAndFinish(closeFrame)

    def onComplete(ctx: Context[FrameStart]): TerminationDirective =
      ctx.absorbTermination()
  }

  private trait CompletionHandlingState extends State {
    def onComplete(ctx: Context[FrameStart]): TerminationDirective
  }

  override def onUpstreamFinish(ctx: Context[FrameStart]): TerminationDirective =
    current.asInstanceOf[CompletionHandlingState].onComplete(ctx)

  override def onUpstreamFailure(cause: scala.Throwable, ctx: Context[FrameStart]): TerminationDirective = cause match {
    case p: ProtocolException ⇒
      become(new SendOutCloseFrameAndComplete(FrameEvent.closeFrame(Protocol.CloseCodes.ProtocolError)))
      ctx.absorbTermination()
    case _ ⇒ super.onUpstreamFailure(cause, ctx)
  }
}

private[http] object FrameOutHandler {
  type Input = AnyRef

  def create(serverSide: Boolean, closeTimeout: FiniteDuration, log: LoggingAdapter): Flow[Input, FrameStart, NotUsed] =
    Flow[Input].transform(() ⇒ new FrameOutHandler(serverSide, closeTimeout, log))
}