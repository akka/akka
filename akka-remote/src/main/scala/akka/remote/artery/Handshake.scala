/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.remote.EndpointManager.Send
import akka.remote.UniqueAddress
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic
import akka.util.OptionVal

/**
 * INTERNAL API
 */
private[akka] object OutboundHandshake {

  /**
   * Stream is failed with this exception if the handshake is not completed
   * within the handshake timeout.
   */
  class HandshakeTimeoutException(msg: String) extends RuntimeException(msg) with NoStackTrace

  // FIXME serialization for these messages
  final case class HandshakeReq(from: UniqueAddress) extends ControlMessage
  final case class HandshakeRsp(from: UniqueAddress) extends Reply

  private sealed trait HandshakeState
  private case object Start extends HandshakeState
  private case object ReqInProgress extends HandshakeState
  private case object Completed extends HandshakeState

  private case object HandshakeTimeout
  private case object HandshakeRetryTick
  private case object InjectHandshakeTick

}

/**
 * INTERNAL API
 */
private[akka] class OutboundHandshake(outboundContext: OutboundContext, timeout: FiniteDuration,
                                      retryInterval: FiniteDuration, injectHandshakeInterval: FiniteDuration)
  extends GraphStage[FlowShape[Send, Send]] {

  val in: Inlet[Send] = Inlet("OutboundHandshake.in")
  val out: Outlet[Send] = Outlet("OutboundHandshake.out")
  override val shape: FlowShape[Send, Send] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {
      import OutboundHandshake._

      private var handshakeState: HandshakeState = Start
      private var pendingMessage: Send = null
      private var injectHandshakeTickScheduled = false

      // InHandler
      override def onPush(): Unit = {
        if (handshakeState != Completed)
          throw new IllegalStateException(s"onPush before handshake completed, was [$handshakeState]")

        // inject a HandshakeReq once in a while to trigger a new handshake when destination
        // system has been restarted
        if (injectHandshakeTickScheduled) {
          push(out, grab(in))
        } else {
          pushHandshakeReq()
          pendingMessage = grab(in)
        }
      }

      // OutHandler
      override def onPull(): Unit = {
        handshakeState match {
          case Completed ⇒
            if (pendingMessage eq null)
              pull(in)
            else {
              push(out, pendingMessage)
              pendingMessage = null
            }

          case Start ⇒
            val uniqueRemoteAddress = outboundContext.associationState.uniqueRemoteAddress
            if (uniqueRemoteAddress.isCompleted) {
              handshakeState = Completed
            } else {
              // will pull when handshake reply is received (uniqueRemoteAddress completed)
              handshakeState = ReqInProgress
              scheduleOnce(HandshakeTimeout, timeout)
              schedulePeriodically(HandshakeRetryTick, retryInterval)

              // The InboundHandshake stage will complete the uniqueRemoteAddress future
              // when it receives the HandshakeRsp reply
              implicit val ec = materializer.executionContext
              uniqueRemoteAddress.foreach {
                getAsyncCallback[UniqueAddress] { a ⇒
                  if (handshakeState != Completed) {
                    handshakeCompleted()
                    if (isAvailable(out))
                      pull(in)
                  }
                }.invoke
              }
            }

            // always push a HandshakeReq as the first message
            pushHandshakeReq()

          case ReqInProgress ⇒ // will pull when handshake reply is received
        }
      }

      private def pushHandshakeReq(): Unit = {
        injectHandshakeTickScheduled = true
        scheduleOnce(InjectHandshakeTick, injectHandshakeInterval)
        push(out, Send(HandshakeReq(outboundContext.localAddress), OptionVal.None, outboundContext.dummyRecipient, None))
      }

      private def handshakeCompleted(): Unit = {
        handshakeState = Completed
        cancelTimer(HandshakeRetryTick)
        cancelTimer(HandshakeTimeout)
      }

      override protected def onTimer(timerKey: Any): Unit =
        timerKey match {
          case InjectHandshakeTick ⇒
            // next onPush message will trigger sending of HandshakeReq
            injectHandshakeTickScheduled = false
          case HandshakeRetryTick ⇒
            if (isAvailable(out))
              pushHandshakeReq()
          case HandshakeTimeout ⇒
            failStage(new HandshakeTimeoutException(
              s"Handshake with [${outboundContext.remoteAddress}] did not complete within ${timeout.toMillis} ms"))
        }

      setHandlers(in, out, this)
    }

}

/**
 * INTERNAL API
 */
private[akka] class InboundHandshake(inboundContext: InboundContext, inControlStream: Boolean) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundHandshake.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundHandshake.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with OutHandler with StageLogging {
      import OutboundHandshake._

      // InHandler
      if (inControlStream)
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val env = grab(in)
            env.message match {
              case HandshakeReq(from) ⇒ onHandshakeReq(from)
              case HandshakeRsp(from) ⇒
                inboundContext.completeHandshake(from)
                pull(in)
              case _ ⇒
                onMessage(env)
            }
          }
        })
      else
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val env = grab(in)
            env.message match {
              case HandshakeReq(from) ⇒ onHandshakeReq(from)
              case _ ⇒
                onMessage(env)
            }
          }
        })

      private def onHandshakeReq(from: UniqueAddress): Unit = {
        inboundContext.completeHandshake(from)
        inboundContext.sendControl(from.address, HandshakeRsp(inboundContext.localAddress))
        pull(in)
      }

      private def onMessage(env: InboundEnvelope): Unit = {
        if (isKnownOrigin(env))
          push(out, env)
        else {
          // FIXME remove, only debug
          log.warning(
            s"Dropping message [{}] from unknown system with UID [{}]. " +
              "This system with UID [{}] was probably restarted. " +
              "Messages will be accepted when new handshake has been completed.",
            env.message.getClass.getName, inboundContext.localAddress.uid, env.originUid)
          if (log.isDebugEnabled)
            log.debug(
              s"Dropping message [{}] from unknown system with UID [{}]. " +
                "This system with UID [{}] was probably restarted. " +
                "Messages will be accepted when new handshake has been completed.",
              env.message.getClass.getName, inboundContext.localAddress.uid, env.originUid)
          pull(in)
        }
      }

      private def isKnownOrigin(env: InboundEnvelope): Boolean =
        env.association.isDefined

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandler(out, this)

    }

}
