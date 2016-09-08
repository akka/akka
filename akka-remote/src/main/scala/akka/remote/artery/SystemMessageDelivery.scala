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
import akka.actor.ActorRef

/**
 * INTERNAL API
 */
private[akka] object SystemMessageDelivery {
  // FIXME serialization of these messages
  // FIXME ackReplyTo should not be needed
  final case class SystemMessageEnvelope(message: AnyRef, seqNo: Long, ackReplyTo: UniqueAddress) extends ArteryMessage
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
  deadLetters:     ActorRef,
  resendInterval:  FiniteDuration,
  maxBufferSize:   Int)
  extends GraphStage[FlowShape[OutboundEnvelope, OutboundEnvelope]] {

  import SystemMessageDelivery._

  val in: Inlet[OutboundEnvelope] = Inlet("SystemMessageDelivery.in")
  val out: Outlet[OutboundEnvelope] = Outlet("SystemMessageDelivery.out")
  override val shape: FlowShape[OutboundEnvelope, OutboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler with ControlMessageObserver {

      private var replyObserverAttached = false
      private var seqNo = 0L // sequence number for the first message will be 1
      private val unacknowledged = new ArrayDeque[OutboundEnvelope]
      private var resending = new ArrayDeque[OutboundEnvelope]
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
        sendUnacknowledgedToDeadLetters()
        unacknowledged.clear()
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
          pushCopy(resending.poll())
      }

      // important to not send the buffered instance, since it's mutable
      private def pushCopy(outboundEnvelope: OutboundEnvelope): Unit = {
        push(out, outboundEnvelope.copy())
      }

      // InHandler
      override def onPush(): Unit = {
        val outboundEnvelope = grab(in)
        outboundEnvelope.message match {
          case _: HandshakeReq ⇒
            // pass on HandshakeReq
            if (isAvailable(out))
              pushCopy(outboundEnvelope)
          case ClearSystemMessageDelivery ⇒
            clear()
            pull(in)
          case _: ControlMessage ⇒
            // e.g. ActorSystemTerminating, no need for acked delivery
            if (resending.isEmpty && isAvailable(out))
              pushCopy(outboundEnvelope)
            else {
              resending.offer(outboundEnvelope)
              tryResend()
            }
          case msg ⇒
            if (unacknowledged.size < maxBufferSize) {
              seqNo += 1
              val sendEnvelope = outboundEnvelope.withMessage(SystemMessageEnvelope(msg, seqNo, localAddress))
              unacknowledged.offer(sendEnvelope)
              scheduleOnce(ResendTick, resendInterval)
              if (resending.isEmpty && isAvailable(out))
                pushCopy(sendEnvelope)
              else {
                resending.offer(sendEnvelope)
                tryResend()
              }
            } else {
              // buffer overflow
              outboundContext.quarantine(reason = s"System message delivery buffer overflow, size [$maxBufferSize]")
              deadLetters ! outboundEnvelope
              pull(in)
            }
        }
      }

      private def clear(): Unit = {
        sendUnacknowledgedToDeadLetters()
        seqNo = 0L // sequence number for the first message will be 1
        unacknowledged.clear()
        resending.clear()
        resendingFromSeqNo = -1L
        cancelTimer(resendInterval)
      }

      private def sendUnacknowledgedToDeadLetters(): Unit = {
        val iter = unacknowledged.iterator
        while (iter.hasNext()) {
          deadLetters ! iter.next()
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

      // TODO we might need have to prune old unused entries
      var sequenceNumbers = Map.empty[UniqueAddress, Long]

      def localAddress = inboundContext.localAddress

      // InHandler
      override def onPush(): Unit = {
        val env = grab(in)
        env.message match {
          case sysEnv @ SystemMessageEnvelope(_, n, ackReplyTo) ⇒
            val expectedSeqNo = sequenceNumbers.get(ackReplyTo) match {
              case None        ⇒ 1L
              case Some(seqNo) ⇒ seqNo
            }
            if (n == expectedSeqNo) {
              inboundContext.sendControl(ackReplyTo.address, Ack(n, localAddress))
              sequenceNumbers = sequenceNumbers.updated(ackReplyTo, n + 1)
              val unwrapped = env.withMessage(sysEnv.message)
              push(out, unwrapped)
            } else if (n < expectedSeqNo) {
              inboundContext.sendControl(ackReplyTo.address, Ack(expectedSeqNo - 1, localAddress))
              pull(in)
            } else {
              inboundContext.sendControl(ackReplyTo.address, Nack(expectedSeqNo - 1, localAddress))
              pull(in)
            }
          case _ ⇒
            // messages that don't need acking
            push(out, env)
        }
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}

