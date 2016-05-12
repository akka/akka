/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import akka.Done
import akka.remote.EndpointManager.Send
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
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
  private case object ControlMessageObserverAttached extends HandshakeState
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
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with ControlMessageObserver {
      import OutboundHandshake._

      private var handshakeState: HandshakeState = Start

      private def remoteAddress = outboundContext.remoteAddress

      override def preStart(): Unit = {
        if (outboundContext.uniqueRemoteAddress.isCompleted) {
          handshakeState = Completed
        } else {
          implicit val ec = materializer.executionContext
          outboundContext.controlSubject.attach(this).foreach {
            getAsyncCallback[Done] { _ ⇒
              if (handshakeState != Completed) {
                if (isAvailable(out))
                  pushHandshakeReq()
                else
                  handshakeState = ControlMessageObserverAttached
              }
            }.invoke
          }

          outboundContext.uniqueRemoteAddress.foreach {
            getAsyncCallback[UniqueAddress] { a ⇒
              if (handshakeState != Completed) {
                handshakeCompleted()
                if (isAvailable(out) && !hasBeenPulled(in))
                  pull(in)
              }
            }.invoke
          }

          scheduleOnce(HandshakeTimeout, timeout)
        }
      }

      override def postStop(): Unit = {
        outboundContext.controlSubject.detach(this)
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
          case ControlMessageObserverAttached ⇒
            pushHandshakeReq()
          case Start         ⇒ // will push HandshakeReq when ControlMessageObserver is attached
          case ReqInProgress ⇒ // will pull when handshake reply is received
        }
      }

      private def pushHandshakeReq(): Unit = {
        handshakeState = ReqInProgress
        // FIXME we should be able to Send without recipient ActorRef
        push(out, Send(HandshakeReq(outboundContext.localAddress), None, outboundContext.dummyRecipient, None))
      }

      private def handshakeCompleted(): Unit = {
        handshakeState = Completed
        cancelTimer(HandshakeTimeout)
        outboundContext.controlSubject.detach(this)
      }

      override protected def onTimer(timerKey: Any): Unit =
        timerKey match {
          case HandshakeTimeout ⇒
            failStage(new TimeoutException(
              s"Handshake with [$remoteAddress] did not complete within ${timeout.toMillis} ms"))
        }

      // ControlMessageObserver, external call
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {
        inboundEnvelope.message match {
          case rsp: HandshakeRsp ⇒
            if (rsp.from.address == remoteAddress) {
              getAsyncCallback[HandshakeRsp] { reply ⇒
                if (handshakeState != Completed) {
                  handshakeCompleted()
                  outboundContext.completeRemoteAddress(reply.from)
                  if (isAvailable(out) && !hasBeenPulled(in))
                    pull(in)
                }
              }.invoke(rsp)
            }
          case _ ⇒ // not interested
        }
      }

      setHandlers(in, out, this)
    }

}

/**
 * INTERNAL API
 */
private[akka] class InboundHandshake(inboundContext: InboundContext) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  val in: Inlet[InboundEnvelope] = Inlet("InboundHandshake.in")
  val out: Outlet[InboundEnvelope] = Outlet("InboundHandshake.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {
      import OutboundHandshake._

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case InboundEnvelope(_, _, HandshakeReq(from), _) ⇒
            inboundContext.association(from.address).completeRemoteAddress(from)
            inboundContext.sendControl(from.address, HandshakeRsp(inboundContext.localAddress))
            pull(in)
          case other ⇒
            push(out, other)
        }
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)

    }

}
