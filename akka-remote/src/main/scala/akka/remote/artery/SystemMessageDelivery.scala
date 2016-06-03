/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.ArrayDeque
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
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
import akka.remote.artery.OutboundHandshake.HandshakeReq

/**
 * INTERNAL API
 */
private[akka] object SystemMessageDelivery {
  // FIXME serialization of these messages
  // FIXME ackReplyTo should not be needed
  final case class SystemMessageEnvelope(message: AnyRef, seqNo: Long, ackReplyTo: UniqueAddress)
  final case class Ack(seqNo: Long, from: UniqueAddress) extends Reply
  final case class Nack(seqNo: Long, from: UniqueAddress) extends Reply

  final case object ClearSystemMessageDelivery

  private case object ResendTick
}

/**
 * INTERNAL API
 */
private[akka] class SystemMessageDelivery(
  outboundContext: OutboundContext,
  resendInterval:  FiniteDuration,
  maxBufferSize:   Int)
  extends GraphStage[FlowShape[Send, Send]] {

  import SystemMessageDelivery._

  val in: Inlet[Send] = Inlet("SystemMessageDelivery.in")
  val out: Outlet[Send] = Outlet("SystemMessageDelivery.out")
  override val shape: FlowShape[Send, Send] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with ControlMessageObserver {

      private var replyObserverAttached = false
      private var seqNo = 0L // sequence number for the first message will be 1
      private val unacknowledged = new ArrayDeque[Send]
      private var resending = new ArrayDeque[Send]
      private var resendingFromSeqNo = -1L
      private var stopping = false

      private def localAddress = outboundContext.localAddress
      private def remoteAddress = outboundContext.remoteAddress

      override def preStart(): Unit = {
        implicit val ec = materializer.executionContext
        outboundContext.controlSubject.attach(this).foreach {
          getAsyncCallback[Done] { _ ⇒
            replyObserverAttached = true
            if (isAvailable(out))
              pull(in) // onPull from downstream already called
          }.invoke
        }

        outboundContext.controlSubject.stopped.onComplete {
          getAsyncCallback[Try[Done]] {
            // FIXME quarantine
            case Success(_)     ⇒ completeStage()
            case Failure(cause) ⇒ failStage(cause)
          }.invoke
        }
      }

      override def postStop(): Unit = {
        outboundContext.controlSubject.detach(this)
      }

      override def onUpstreamFinish(): Unit = {
        if (unacknowledged.isEmpty)
          super.onUpstreamFinish()
        else
          stopping = true
      }

      override protected def onTimer(timerKey: Any): Unit =
        timerKey match {
          case ResendTick ⇒
            if (resending.isEmpty && !unacknowledged.isEmpty) {
              resending = unacknowledged.clone()
              tryResend()
            }
            if (!unacknowledged.isEmpty)
              scheduleOnce(ResendTick, resendInterval)
          // FIXME give up resending after a long while, i.e. config property quarantine-after-silence
        }

      // ControlMessageObserver, external call
      override def notify(inboundEnvelope: InboundEnvelope): Unit = {
        inboundEnvelope.message match {
          case ack: Ack   ⇒ if (ack.from.address == remoteAddress) ackCallback.invoke(ack)
          case nack: Nack ⇒ if (nack.from.address == remoteAddress) nackCallback.invoke(nack)
          case _          ⇒ // not interested
        }
      }

      private val ackCallback = getAsyncCallback[Ack] { reply ⇒
        ack(reply.seqNo)
      }

      private val nackCallback = getAsyncCallback[Nack] { reply ⇒
        if (reply.seqNo <= seqNo) {
          ack(reply.seqNo)
          if (reply.seqNo > resendingFromSeqNo)
            resending = unacknowledged.clone()
          tryResend()
        }
      }

      private def ack(n: Long): Unit = {
        if (n <= seqNo)
          clearUnacknowledged(n)
      }

      @tailrec private def clearUnacknowledged(ackedSeqNo: Long): Unit = {
        if (!unacknowledged.isEmpty &&
          unacknowledged.peek().message.asInstanceOf[SystemMessageEnvelope].seqNo <= ackedSeqNo) {
          unacknowledged.removeFirst()
          if (unacknowledged.isEmpty)
            cancelTimer(resendInterval)

          if (stopping && unacknowledged.isEmpty)
            completeStage()
          else
            clearUnacknowledged(ackedSeqNo)
        }
      }

      private def tryResend(): Unit = {
        if (isAvailable(out) && !resending.isEmpty)
          push(out, resending.poll())
      }

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case s @ Send(_: HandshakeReq, _, _, _) ⇒
            // pass on HandshakeReq
            if (isAvailable(out))
              push(out, s)
          case s @ Send(ClearSystemMessageDelivery, _, _, _) ⇒
            clear()
            pull(in)
          case s @ Send(msg: AnyRef, _, _, _) ⇒
            if (unacknowledged.size < maxBufferSize) {
              seqNo += 1
              val sendMsg = s.copy(message = SystemMessageEnvelope(msg, seqNo, localAddress))
              unacknowledged.offer(sendMsg)
              scheduleOnce(ResendTick, resendInterval)
              if (resending.isEmpty && isAvailable(out))
                push(out, sendMsg)
              else {
                resending.offer(sendMsg)
                tryResend()
              }
            } else {
              // buffer overflow
              outboundContext.quarantine(reason = s"System message delivery buffer overflow, size [$maxBufferSize]")
              pull(in)
            }
        }
      }

      private def clear(): Unit = {
        seqNo = 0L // sequence number for the first message will be 1
        unacknowledged.clear()
        resending.clear()
        resendingFromSeqNo = -1L
        cancelTimer(resendInterval)
      }

      // OutHandler
      override def onPull(): Unit = {
        if (replyObserverAttached) { // otherwise it will be pulled after attached
          if (resending.isEmpty && !hasBeenPulled(in) && !stopping)
            pull(in)
          else
            tryResend()
        }
      }

      setHandlers(in, out, this)
    }
}

/**
 * INTERNAL API
 */
private[akka] class SystemMessageAcker(inboundContext: InboundContext) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  import SystemMessageDelivery._

  val in: Inlet[InboundEnvelope] = Inlet("SystemMessageAcker.in")
  val out: Outlet[InboundEnvelope] = Outlet("SystemMessageAcker.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      var seqNo = 1L

      def localAddress = inboundContext.localAddress

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case env @ InboundEnvelope(_, _, sysEnv @ SystemMessageEnvelope(_, n, ackReplyTo), _, _) ⇒
            if (n == seqNo) {
              inboundContext.sendControl(ackReplyTo.address, Ack(n, localAddress))
              seqNo += 1
              val unwrapped = env.copy(message = sysEnv.message)
              push(out, unwrapped)
            } else if (n < seqNo) {
              inboundContext.sendControl(ackReplyTo.address, Ack(n, localAddress))
              pull(in)
            } else {
              inboundContext.sendControl(ackReplyTo.address, Nack(seqNo - 1, localAddress))
              pull(in)
            }
          case env ⇒
            // messages that don't need acking
            push(out, env)
        }

      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

