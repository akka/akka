/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeoutException

import scala.concurrent.duration._
import scala.util.Success

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

/**
 * INTERNAL API
 */
private[akka] object OutboundHandshake {
  // FIXME serialization for these messages
  final case class HandshakeReq(from: UniqueAddress) extends ControlMessage
  final case class HandshakeRsp(from: UniqueAddress) extends Reply

  private sealed trait HandshakeState
  private case object Start extends HandshakeState
  private case object ReqInProgress extends HandshakeState
  private case object Completed extends HandshakeState

  private case object HandshakeTimeout

}

/**
 * INTERNAL API
 */
private[akka] class OutboundHandshake(outboundContext: OutboundContext, timeout: FiniteDuration) extends GraphStage[FlowShape[Send, Send]] {
  val in: Inlet[Send] = Inlet("OutboundHandshake.in")
  val out: Outlet[Send] = Outlet("OutboundHandshake.out")
  override val shape: FlowShape[Send, Send] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {
      import OutboundHandshake._

      private var handshakeState: HandshakeState = Start

      override def preStart(): Unit = {
        val uniqueRemoteAddress = outboundContext.associationState.uniqueRemoteAddress
        if (uniqueRemoteAddress.isCompleted) {
          handshakeState = Completed
        } else {
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

          scheduleOnce(HandshakeTimeout, timeout)
        }
      }

      // InHandler
      override def onPush(): Unit = {
        if (handshakeState != Completed)
          throw new IllegalStateException(s"onPush before handshake completed, was [$handshakeState]")
        push(out, grab(in))
      }

      // OutHandler
      override def onPull(): Unit = {
        handshakeState match {
          case Completed ⇒ pull(in)
          case Start ⇒
            // will pull when handshake reply is received (uniqueRemoteAddress completed)
            handshakeState = ReqInProgress
            outboundContext.sendControl(HandshakeReq(outboundContext.localAddress))
          case ReqInProgress ⇒ // will pull when handshake reply is received
        }
      }

      private def handshakeCompleted(): Unit = {
        handshakeState = Completed
        cancelTimer(HandshakeTimeout)
      }

      override protected def onTimer(timerKey: Any): Unit =
        timerKey match {
          case HandshakeTimeout ⇒
            // FIXME would it make sense to retry a few times before failing?
            failStage(new TimeoutException(
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
    new TimerGraphStageLogic(shape) with OutHandler {
      import OutboundHandshake._

      // InHandler
      if (inControlStream)
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            grab(in) match {
              case InboundEnvelope(_, _, HandshakeReq(from), _, _) ⇒
                inboundContext.association(from.address).completeHandshake(from)
                inboundContext.sendControl(from.address, HandshakeRsp(inboundContext.localAddress))
                pull(in)
              case InboundEnvelope(_, _, HandshakeRsp(from), _, _) ⇒
                inboundContext.association(from.address).completeHandshake(from)
                pull(in)
              case other ⇒ onMessage(other)
            }
          }
        })
      else
        setHandler(in, new InHandler {
          override def onPush(): Unit = onMessage(grab(in))
        })

      private def onMessage(env: InboundEnvelope): Unit = {
        if (isKnownOrigin(env.originAddress))
          push(out, env)
        else {
          inboundContext.sendControl(env.originAddress.address, HandshakeReq(inboundContext.localAddress))
          // FIXME Note that we have the originAddress that would be needed to complete the handshake
          //       but it is not done here because the handshake might exchange more information.
          //       Is that a valid thought?
          // drop message from unknown, this system was probably restarted
          pull(in)
        }
      }

      private def isKnownOrigin(originAddress: UniqueAddress): Boolean = {
        // FIXME these association lookups are probably too costly for each message, need local cache or something
        val associationState = inboundContext.association(originAddress.address).associationState
        associationState.uniqueRemoteAddressValue() match {
          case Some(Success(a)) if a.uid == originAddress.uid ⇒ true
          case x ⇒ false
        }
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandler(out, this)

    }

}
