/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.ArrayDeque

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.Done
import akka.actor.ActorRef
import akka.actor.Address
import akka.remote.EndpointManager.Send
import akka.remote.artery.Transport.InboundEnvelope
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.stage.AsyncCallback
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic

/**
 * INTERNAL API
 */
private[akka] object SystemMessageDelivery {
  // FIXME serialization of these messages
  final case class SystemMessageEnvelope(message: AnyRef, seqNo: Long, ackReplyTo: ActorRef)
  sealed trait SystemMessageReply
  final case class Ack(seq: Long, from: Address) extends SystemMessageReply
  final case class Nack(seq: Long, from: Address) extends SystemMessageReply

  private case object ResendTick
}

/**
 * INTERNAL API
 */
private[akka] class SystemMessageDelivery(
  replyJunction: SystemMessageReplyJunction.Junction,
  resendInterval: FiniteDuration,
  localAddress: Address,
  remoteAddress: Address,
  ackRecipient: ActorRef)
  extends GraphStage[FlowShape[Send, Send]] {

  import SystemMessageDelivery._
  import SystemMessageReplyJunction._

  val in: Inlet[Send] = Inlet("SystemMessageDelivery.in")
  val out: Outlet[Send] = Outlet("SystemMessageDelivery.out")
  override val shape: FlowShape[Send, Send] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler with OutHandler {

      var registered = false
      var seqNo = 0L // sequence number for the first message will be 1
      val unacknowledged = new ArrayDeque[Send]
      var resending = new ArrayDeque[Send]
      var resendingFromSeqNo = -1L
      var stopping = false

      override def preStart(): Unit = {
        this.schedulePeriodically(ResendTick, resendInterval)
        def filter(env: InboundEnvelope): Boolean =
          env.message match {
            case Ack(_, from) if from == remoteAddress  ⇒ true
            case Nack(_, from) if from == remoteAddress ⇒ true
            case _                                      ⇒ false
          }

        implicit val ec = materializer.executionContext
        replyJunction.addReplyInterest(filter, ackCallback).foreach {
          getAsyncCallback[Done] { _ ⇒
            registered = true
            if (isAvailable(out))
              pull(in) // onPull from downstream already called
          }.invoke
        }

        replyJunction.stopped.onComplete {
          getAsyncCallback[Try[Done]] {
            // FIXME quarantine
            case Success(_)     ⇒ completeStage()
            case Failure(cause) ⇒ failStage(cause)
          }.invoke
        }
      }

      override def postStop(): Unit = {
        replyJunction.removeReplyInterest(ackCallback)
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

      val ackCallback = getAsyncCallback[SystemMessageReply] { reply ⇒
        reply match {
          case Ack(n, _) ⇒
            ack(n)
          case Nack(n, _) ⇒
            ack(n)
            if (n > resendingFromSeqNo)
              resending = unacknowledged.clone()
            tryResend()
        }
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
          case s @ Send(reply: SystemMessageReply, _, _, _) ⇒
            // pass through
            if (isAvailable(out))
              push(out, s)
            else {
              // it's ok to drop the replies, but we can try
              resending.offer(s)
            }

          case s @ Send(msg: AnyRef, _, _, _) ⇒
            seqNo += 1
            val sendMsg = s.copy(message = SystemMessageEnvelope(msg, seqNo, ackRecipient))
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
        if (registered) { // otherwise it will be pulled after replyJunction.addReplyInterest
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
private[akka] class SystemMessageAcker(localAddress: Address) extends GraphStage[FlowShape[InboundEnvelope, InboundEnvelope]] {
  import SystemMessageDelivery._

  val in: Inlet[InboundEnvelope] = Inlet("SystemMessageAcker.in")
  val out: Outlet[InboundEnvelope] = Outlet("SystemMessageAcker.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      var seqNo = 1L

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case env @ InboundEnvelope(_, _, sysEnv @ SystemMessageEnvelope(_, n, ackReplyTo), _) ⇒
            if (n == seqNo) {
              ackReplyTo.tell(Ack(n, localAddress), ActorRef.noSender)
              seqNo += 1
              val unwrapped = env.copy(message = sysEnv.message)
              push(out, unwrapped)
            } else if (n < seqNo) {
              ackReplyTo.tell(Ack(n, localAddress), ActorRef.noSender)
              pull(in)
            } else {
              ackReplyTo.tell(Nack(seqNo - 1, localAddress), ActorRef.noSender)
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

/**
 * INTERNAL API
 */
private[akka] object SystemMessageReplyJunction {
  import SystemMessageDelivery._

  trait Junction {
    def addReplyInterest(filter: InboundEnvelope ⇒ Boolean, replyCallback: AsyncCallback[SystemMessageReply]): Future[Done]
    def removeReplyInterest(callback: AsyncCallback[SystemMessageReply]): Unit
    def stopped: Future[Done]
  }
}

/**
 * INTERNAL API
 */
private[akka] class SystemMessageReplyJunction
  extends GraphStageWithMaterializedValue[FlowShape[InboundEnvelope, InboundEnvelope], SystemMessageReplyJunction.Junction] {
  import SystemMessageReplyJunction._
  import SystemMessageDelivery._

  val in: Inlet[InboundEnvelope] = Inlet("SystemMessageReplyJunction.in")
  val out: Outlet[InboundEnvelope] = Outlet("SystemMessageReplyJunction.out")
  override val shape: FlowShape[InboundEnvelope, InboundEnvelope] = FlowShape(in, out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val logic = new GraphStageLogic(shape) with InHandler with OutHandler with Junction {

      private var replyHandlers: Vector[(InboundEnvelope ⇒ Boolean, AsyncCallback[SystemMessageReply])] = Vector.empty
      private val stoppedPromise = Promise[Done]()

      override def postStop(): Unit = stoppedPromise.success(Done)

      // InHandler
      override def onPush(): Unit = {
        grab(in) match {
          case env @ InboundEnvelope(_, _, reply: SystemMessageReply, _) ⇒
            replyHandlers.foreach {
              case (f, callback) ⇒
                if (f(env))
                  callback.invoke(reply)
            }
            pull(in)
          case env ⇒
            push(out, env)
        }
      }

      // OutHandler
      override def onPull(): Unit = pull(in)

      override def addReplyInterest(filter: InboundEnvelope ⇒ Boolean, replyCallback: AsyncCallback[SystemMessageReply]): Future[Done] = {
        val p = Promise[Done]()
        getAsyncCallback[Unit](_ ⇒ {
          replyHandlers :+= (filter -> replyCallback)
          p.success(Done)
        }).invoke(())
        p.future
      }

      override def removeReplyInterest(callback: AsyncCallback[SystemMessageReply]): Unit = {
        replyHandlers = replyHandlers.filterNot { case (_, c) ⇒ c == callback }
      }

      override def stopped: Future[Done] = stoppedPromise.future

      setHandlers(in, out, this)
    }
    (logic, logic)
  }
}
