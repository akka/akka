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
import akka.remote.artery.ReplyJunction.ReplyObserver
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
private[akka] object SystemMessageDelivery {
  // FIXME serialization of these messages
  // FIXME ackReplyTo should not be needed
  final case class SystemMessageEnvelope(message: AnyRef, seqNo: Long, ackReplyTo: UniqueAddress)
  final case class Ack(seqNo: Long, from: UniqueAddress) extends Reply
  final case class Nack(seqNo: Long, from: UniqueAddress) extends Reply

  private case object ResendTick
}

/**
 * INTERNAL API
 */
private[akka] class SystemMessageDelivery(
  outboundContext: OutboundContext,
  resendInterval: FiniteDuration)
  extends GraphStage[FlowShape[Send, Send]] {

  import SystemMessageDelivery._

  val in: Inlet[Send] = Inlet("SystemMessageDelivery.in")
  val out: Outlet[Send] = Outlet("SystemMessageDelivery.out")
  override val shape: FlowShape[Send, Send] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with ReplyObserver {

      private var replyObserverAttached = false
      private var seqNo = 0L // sequence number for the first message will be 1
      private val unacknowledged = new ArrayDeque[Send]
      private var resending = new ArrayDeque[Send]
      private var resendingFromSeqNo = -1L
      private var stopping = false

      private def localAddress = outboundContext.localAddress
      private def remoteAddress = outboundContext.remoteAddress

      override def preStart(): Unit = {
        this.schedulePeriodically(ResendTick, resendInterval)

        implicit val ec = materializer.executionContext
        outboundContext.replySubject.attach(this).foreach {
          getAsyncCallback[Done] { _ ⇒
            replyObserverAttached = true
            if (isAvailable(out))
              pull(in) // onPull from downstream already called
          }.invoke
        }

        outboundContext.replySubject.stopped.onComplete {
          getAsyncCallback[Try[Done]] {
            // FIXME quarantine
            case Success(_)     ⇒ completeStage()
            case Failure(cause) ⇒ failStage(cause)
          }.invoke
        }
      }

      override def postStop(): Unit = {
        outboundContext.replySubject.detach(this)
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
        }

      // ReplyObserver, external call
      override def reply(inboundEnvelope: InboundEnvelope): Unit = {
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
        ack(reply.seqNo)
        if (reply.seqNo > resendingFromSeqNo)
          resending = unacknowledged.clone()
        tryResend()
      }

      private def ack(n: Long): Unit = {
        if (n > seqNo)
          throw new IllegalArgumentException(s"Unexpected ack $n, when highest sent seqNo is $seqNo")
        clearUnacknowledged(n)
      }

      @tailrec private def clearUnacknowledged(ackedSeqNo: Long): Unit = {
        if (!unacknowledged.isEmpty &&
          unacknowledged.peek().message.asInstanceOf[SystemMessageEnvelope].seqNo <= ackedSeqNo) {
          unacknowledged.removeFirst()
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
          case s @ Send(reply: ControlMessage, _, _, _) ⇒
            // pass through
            if (isAvailable(out))
              push(out, s)
            else {
              // it's ok to drop the replies, but we can try
              resending.offer(s)
            }

          case s @ Send(msg: AnyRef, _, _, _) ⇒
            seqNo += 1
            val sendMsg = s.copy(message = SystemMessageEnvelope(msg, seqNo, localAddress))
            // FIXME quarantine if unacknowledged is full
            unacknowledged.offer(sendMsg)
            if (resending.isEmpty && isAvailable(out))
              push(out, sendMsg)
            else {
              resending.offer(sendMsg)
              tryResend()
            }
        }
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
          case env @ InboundEnvelope(_, _, sysEnv @ SystemMessageEnvelope(_, n, ackReplyTo), _) ⇒
            if (n == seqNo) {
              inboundContext.sendReply(ackReplyTo.address, Ack(n, localAddress))
              seqNo += 1
              val unwrapped = env.copy(message = sysEnv.message)
              push(out, unwrapped)
            } else if (n < seqNo) {
              inboundContext.sendReply(ackReplyTo.address, Ack(n, localAddress))
              pull(in)
            } else {
              inboundContext.sendReply(ackReplyTo.address, Nack(seqNo - 1, localAddress))
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

