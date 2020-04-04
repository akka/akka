/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery.internal

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.ConsumerController.DeliverThenStop
import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.internal.ActorFlightRecorder
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.StashBuffer
import akka.actor.typed.scaladsl.TimerScheduler
import akka.util.ConstantFun.scalaIdentityFunction
import akka.annotation.InternalApi

/**
 * INTERNAL API
 *
 * ==== Design notes ====
 *
 * The destination consumer will start the flow by sending an initial `Start` message
 * to the `ConsumerController`.
 *
 * The `ProducerController` sends the first message to the `ConsumerController` without waiting for
 * a `Request` from the `ConsumerController`. The main reason for this is that when used with
 * Cluster Sharding the first message will typically create the `ConsumerController`. It's
 * also a way to connect the ProducerController and ConsumerController in a dynamic way, for
 * example when the ProducerController is replaced.
 *
 * The `ConsumerController` sends [[ProducerControllerImpl.Request]] to the `ProducerController`
 * to specify it's ready to receive up to the requested sequence number.
 *
 * The `ConsumerController` sends the first `Request` when it receives the first `SequencedMessage`
 * and has received the `Start` message from the consumer.
 *
 * It sends new `Request` when half of the requested window is remaining, but it also retries
 * the `Request` if no messages are received because that could be caused by lost messages.
 *
 * Apart from the first message the producer will not send more messages than requested.
 *
 * Received messages are wrapped in [[ConsumerController.Delivery]] when sent to the consumer,
 * which is supposed to reply with [[ConsumerController.Confirmed]] when it has processed the message.
 * Next message is not delivered until the previous is confirmed.
 * More messages from the producer that arrive while waiting for the confirmation are stashed by
 * the `ConsumerController` and delivered when previous message was confirmed.
 *
 * In other words, the "request" protocol to the application producer and consumer is one-by-one, but
 * between the `ProducerController` and `ConsumerController` it's window of messages in flight.
 *
 * The consumer and the `ConsumerController` are supposed to be local so that these messages are fast and not lost.
 *
 * If the `ConsumerController` receives a message with unexpected sequence number (not previous + 1)
 * it sends [[ProducerControllerImpl.Resend]] to the `ProducerController` and will ignore all messages until
 * the expected sequence number arrives.
 */
@InternalApi private[akka] object ConsumerControllerImpl {
  import ConsumerController.Command
  import ConsumerController.RegisterToProducerController
  import ConsumerController.SeqNr
  import ConsumerController.SequencedMessage
  import ConsumerController.Start

  sealed trait InternalCommand

  /** For commands defined in public ConsumerController */
  trait UnsealedInternalCommand extends InternalCommand

  private final case object Retry extends InternalCommand

  private final case class ConsumerTerminated(consumer: ActorRef[_]) extends InternalCommand

  private final case class State[A](
      producerController: ActorRef[ProducerControllerImpl.InternalCommand],
      producerId: String,
      consumer: ActorRef[ConsumerController.Delivery[A]],
      receivedSeqNr: SeqNr,
      confirmedSeqNr: SeqNr,
      requestedSeqNr: SeqNr,
      registering: Option[ActorRef[ProducerController.Command[A]]],
      stopping: Boolean) {

    def isNextExpected(seqMsg: SequencedMessage[A]): Boolean =
      seqMsg.seqNr == receivedSeqNr + 1

    def isProducerChanged(seqMsg: SequencedMessage[A]): Boolean =
      seqMsg.producerController != producerController || receivedSeqNr == 0

    def updatedRegistering(seqMsg: SequencedMessage[A]): Option[ActorRef[ProducerController.Command[A]]] = {
      registering match {
        case None          => None
        case s @ Some(reg) => if (seqMsg.producerController == reg) None else s
      }
    }
  }

  def apply[A](
      serviceKey: Option[ServiceKey[Command[A]]],
      settings: ConsumerController.Settings): Behavior[Command[A]] = {
    Behaviors
      .withStash[InternalCommand](settings.flowControlWindow) { stashBuffer =>
        Behaviors.setup { context =>
          val flightRecorder = ActorFlightRecorder(context.system).delivery
          flightRecorder.consumerCreated(context.self.path)
          Behaviors.withMdc(msg => mdcForMessage(msg)) {
            context.setLoggerName("akka.actor.typed.delivery.ConsumerController")
            serviceKey.foreach { key =>
              context.system.receptionist ! Receptionist.Register(key, context.self)
            }
            Behaviors.withTimers { timers =>
              // wait for the `Start` message from the consumer, SequencedMessage will be stashed
              def waitForStart(registering: Option[ActorRef[ProducerController.Command[A]]], stopping: Boolean)
                  : Behavior[InternalCommand] = {
                Behaviors.receiveMessagePartial {
                  case reg: RegisterToProducerController[A] @unchecked =>
                    reg.producerController ! ProducerController.RegisterConsumer(context.self)
                    waitForStart(Some(reg.producerController), stopping)

                  case s: Start[A] @unchecked =>
                    ConsumerControllerImpl.enforceLocalConsumer(s.deliverTo)
                    context.watchWith(s.deliverTo, ConsumerTerminated(s.deliverTo))

                    flightRecorder.consumerStarted(context.self.path)
                    val activeBehavior =
                      new ConsumerControllerImpl[A](context, timers, stashBuffer, settings)
                        .active(initialState(context, s, registering, stopping))
                    context.log.debug("Received Start, unstash [{}] messages.", stashBuffer.size)
                    stashBuffer.unstash(activeBehavior, 1, scalaIdentityFunction)

                  case seqMsg: SequencedMessage[A] @unchecked =>
                    if (stashBuffer.isFull) {
                      flightRecorder.consumerStashFull(seqMsg.producerId, seqMsg.seqNr)
                      context.log.debug(
                        "Received SequencedMessage seqNr [{}], stashing before Start, discarding message because stash is full.",
                        seqMsg.seqNr)
                    } else {
                      context.log.trace(
                        "Received SequencedMessage seqNr [{}], stashing before Start, stashed size [{}].",
                        seqMsg.seqNr,
                        stashBuffer.size + 1)
                      stashBuffer.stash(seqMsg)
                    }
                    Behaviors.same

                  case _: DeliverThenStop[_] =>
                    if (stashBuffer.isEmpty) {
                      Behaviors.stopped
                    } else {
                      waitForStart(registering, stopping = true)
                    }

                  case Retry =>
                    registering.foreach { reg =>
                      context.log.debug("Retry sending RegisterConsumer to [{}].", reg)
                      reg ! ProducerController.RegisterConsumer(context.self)
                    }
                    Behaviors.same

                  case ConsumerTerminated(c) =>
                    context.log.debug("Consumer [{}] terminated.", c)
                    Behaviors.stopped

                }

              }

              timers.startTimerWithFixedDelay(Retry, Retry, settings.resendInterval)
              waitForStart(None, stopping = false)
            }
          }
        }
      }
      .narrow // expose Command, but not InternalCommand
  }

  private def mdcForMessage(msg: InternalCommand): Map[String, String] = {
    msg match {
      case seqMsg: SequencedMessage[_] => Map("producerId" -> seqMsg.producerId)
      case _                           => Map.empty
    }
  }

  private def initialState[A](
      context: ActorContext[InternalCommand],
      start: Start[A],
      registering: Option[ActorRef[ProducerController.Command[A]]],
      stopping: Boolean): State[A] = {
    State(
      producerController = context.system.deadLetters,
      "n/a",
      start.deliverTo,
      receivedSeqNr = 0,
      confirmedSeqNr = 0,
      requestedSeqNr = 0,
      registering,
      stopping)
  }

  def enforceLocalConsumer(ref: ActorRef[_]): Unit = {
    if (ref.path.address.hasGlobalScope)
      throw new IllegalArgumentException(s"Consumer [$ref] should be local.")
  }
}

private class ConsumerControllerImpl[A](
    context: ActorContext[ConsumerControllerImpl.InternalCommand],
    timers: TimerScheduler[ConsumerControllerImpl.InternalCommand],
    stashBuffer: StashBuffer[ConsumerControllerImpl.InternalCommand],
    settings: ConsumerController.Settings) {

  import ConsumerController.Confirmed
  import ConsumerController.Delivery
  import ConsumerController.RegisterToProducerController
  import ConsumerController.SequencedMessage
  import ConsumerController.Start
  import ConsumerControllerImpl._
  import ProducerControllerImpl.Ack
  import ProducerControllerImpl.Request
  import ProducerControllerImpl.Resend
  import settings.flowControlWindow

  private val flightRecorder = ActorFlightRecorder(context.system).delivery

  startRetryTimer()

  private def resendLost = !settings.onlyFlowControl

  // Expecting a SequencedMessage from ProducerController, that will be delivered to the consumer if
  // the seqNr is right.
  private def active(s: State[A]): Behavior[InternalCommand] = {
    Behaviors
      .receiveMessage[InternalCommand] {
        case seqMsg: SequencedMessage[A] =>
          val pid = seqMsg.producerId
          val seqNr = seqMsg.seqNr
          val expectedSeqNr = s.receivedSeqNr + 1

          flightRecorder.consumerReceived(pid, seqNr)

          if (s.isProducerChanged(seqMsg)) {
            if (seqMsg.first)
              context.log.trace("Received first SequencedMessage seqNr [{}], delivering to consumer.", seqNr)
            receiveChangedProducer(s, seqMsg)
          } else if (s.registering.isDefined) {
            context.log.debug(
              "Received SequencedMessage seqNr [{}], discarding message because registering to new ProducerController.",
              seqNr)
            stashBuffer.unstash(Behaviors.same, 1, scalaIdentityFunction)
          } else if (s.isNextExpected(seqMsg)) {
            context.log.trace("Received SequencedMessage seqNr [{}], delivering to consumer.", seqNr)
            deliver(s.copy(receivedSeqNr = seqNr), seqMsg)
          } else if (seqNr > expectedSeqNr) {
            flightRecorder.consumerMissing(pid, expectedSeqNr, seqNr)
            context.log.debugN(
              "Received SequencedMessage seqNr [{}], but expected [{}], {}.",
              seqNr,
              expectedSeqNr,
              if (resendLost) "requesting resend from expected seqNr" else "delivering to consumer anyway")
            if (resendLost) {
              seqMsg.producerController ! Resend(fromSeqNr = expectedSeqNr)
              stashBuffer.clear()
              resending(s)
            } else {
              deliver(s.copy(receivedSeqNr = seqNr), seqMsg)
            }
          } else { // seqNr < expectedSeqNr
            flightRecorder.consumerDuplicate(pid, expectedSeqNr, seqNr)
            context.log.debug2("Received duplicate SequencedMessage seqNr [{}], expected [{}].", seqNr, expectedSeqNr)
            if (seqMsg.first)
              stashBuffer.unstash(active(retryRequest(s)), 1, scalaIdentityFunction)
            else
              stashBuffer.unstash(Behaviors.same, 1, scalaIdentityFunction)
          }

        case Retry =>
          receiveRetry(s, () => active(retryRequest(s)))

        case Confirmed =>
          receiveUnexpectedConfirmed()

        case start: Start[A] =>
          receiveStart(s, start, newState => active(newState))

        case ConsumerTerminated(c) =>
          receiveConsumerTerminated(c)

        case reg: RegisterToProducerController[A] =>
          receiveRegisterToProducerController(s, reg, newState => active(newState))

        case _: DeliverThenStop[_] =>
          receiveDeliverThenStop(s, newState => active(newState))

        case _: UnsealedInternalCommand =>
          Behaviors.unhandled
      }
      .receiveSignal {
        case (_, PostStop) => postStop(s)
      }
  }

  private def receiveChangedProducer(s: State[A], seqMsg: SequencedMessage[A]): Behavior[InternalCommand] = {
    val seqNr = seqMsg.seqNr

    if (seqMsg.first || !resendLost) {
      logChangedProducer(s, seqMsg)

      val newRequestedSeqNr = seqMsg.seqNr - 1 + flowControlWindow
      context.log.debug("Sending Request with requestUpToSeqNr [{}] after first SequencedMessage.", newRequestedSeqNr)
      seqMsg.producerController ! Request(confirmedSeqNr = 0L, newRequestedSeqNr, resendLost, viaTimeout = false)

      deliver(
        s.copy(
          producerController = seqMsg.producerController,
          producerId = seqMsg.producerId,
          receivedSeqNr = seqNr,
          confirmedSeqNr = 0L,
          requestedSeqNr = newRequestedSeqNr,
          registering = s.updatedRegistering(seqMsg)),
        seqMsg)
    } else if (s.receivedSeqNr == 0) {
      // needed for sharding
      context.log.debug(
        "Received SequencedMessage seqNr [{}], from new producer producer [{}] but it wasn't first. Resending.",
        seqNr,
        seqMsg.producerController)
      // request resend of all unconfirmed, and mark first
      seqMsg.producerController ! Resend(0)
      stashBuffer.clear()
      resending(s)
    } else {
      context.log.warnN(
        "Received SequencedMessage seqNr [{}], discarding message because it was from unexpected " +
        "producer [{}] when expecting [{}].",
        seqNr,
        seqMsg.producerController,
        s.producerController)
      stashBuffer.unstash(Behaviors.same, 1, scalaIdentityFunction)
    }

  }

  private def logChangedProducer(s: State[A], seqMsg: SequencedMessage[A]): Unit = {
    if (s.producerController == context.system.deadLetters) {
      context.log.debugN(
        "Associated with new ProducerController [{}], seqNr [{}].",
        seqMsg.producerController,
        seqMsg.seqNr)
    } else {
      flightRecorder.consumerChangedProducer(seqMsg.producerId)
      context.log.debugN(
        "Changing ProducerController from [{}] to [{}], seqNr [{}].",
        s.producerController,
        seqMsg.producerController,
        seqMsg.seqNr)
    }
  }

  // It has detected a missing seqNr and requested a Resend. Expecting a SequencedMessage from the
  // ProducerController with the missing seqNr. Other SequencedMessage with different seqNr will be
  // discarded since they were in flight before the Resend request and will anyway be sent again.
  private def resending(s: State[A]): Behavior[InternalCommand] = {
    if (stashBuffer.nonEmpty)
      throw new IllegalStateException("StashBuffer should be cleared before resending.")
    Behaviors
      .receiveMessage[InternalCommand] {
        case seqMsg: SequencedMessage[A] =>
          val seqNr = seqMsg.seqNr

          if (s.isProducerChanged(seqMsg)) {
            if (seqMsg.first)
              context.log.trace("Received first SequencedMessage seqNr [{}], delivering to consumer.", seqNr)
            receiveChangedProducer(s, seqMsg)
          } else if (s.registering.isDefined) {
            context.log.debug(
              "Received SequencedMessage seqNr [{}], discarding message because registering to new ProducerController.",
              seqNr)
            Behaviors.same
          } else if (s.isNextExpected(seqMsg)) {
            flightRecorder.consumerReceivedResend(seqNr)
            context.log.debug("Received missing SequencedMessage seqNr [{}].", seqNr)
            deliver(s.copy(receivedSeqNr = seqNr), seqMsg)
          } else {
            context.log.debug2(
              "Received SequencedMessage seqNr [{}], discarding message because waiting for [{}].",
              seqNr,
              s.receivedSeqNr + 1)
            if (seqMsg.first)
              retryRequest(s)
            Behaviors.same // ignore until we receive the expected
          }

        case Retry =>
          receiveRetry(
            s,
            () => {
              // in case the Resend message was lost
              context.log.debug("Retry sending Resend [{}].", s.receivedSeqNr + 1)
              s.producerController ! Resend(fromSeqNr = s.receivedSeqNr + 1)
              Behaviors.same
            })

        case Confirmed =>
          receiveUnexpectedConfirmed()

        case start: Start[A] =>
          receiveStart(s, start, newState => resending(newState))

        case ConsumerTerminated(c) =>
          receiveConsumerTerminated(c)

        case reg: RegisterToProducerController[A] =>
          receiveRegisterToProducerController(s, reg, newState => active(newState))

        case _: DeliverThenStop[_] =>
          receiveDeliverThenStop(s, newState => resending(newState))

        case _: UnsealedInternalCommand =>
          Behaviors.unhandled
      }
      .receiveSignal {
        case (_, PostStop) => postStop(s)
      }
  }

  private def deliver(s: State[A], seqMsg: SequencedMessage[A]): Behavior[InternalCommand] = {
    s.consumer ! Delivery(seqMsg.message, context.self, seqMsg.producerId, seqMsg.seqNr)
    waitingForConfirmation(s, seqMsg)
  }

  // The message has been delivered to the consumer and it is now waiting for Confirmed from
  // the consumer. New SequencedMessage from the ProducerController will be stashed.
  private def waitingForConfirmation(s: State[A], seqMsg: SequencedMessage[A]): Behavior[InternalCommand] = {
    Behaviors
      .receiveMessage[InternalCommand] {
        case Confirmed =>
          val seqNr = seqMsg.seqNr
          context.log.trace("Received Confirmed seqNr [{}] from consumer, stashed size [{}].", seqNr, stashBuffer.size)

          val newRequestedSeqNr =
            if (seqMsg.first) {
              // confirm the first message immediately to cancel resending of first
              val newRequestedSeqNr = seqNr - 1 + flowControlWindow
              flightRecorder.consumerSentRequest(seqMsg.producerId, newRequestedSeqNr)
              context.log.debug(
                "Sending Request after first with confirmedSeqNr [{}], requestUpToSeqNr [{}].",
                seqNr,
                newRequestedSeqNr)
              s.producerController ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, resendLost, viaTimeout = false)
              newRequestedSeqNr
            } else if ((s.requestedSeqNr - seqNr) == flowControlWindow / 2) {
              val newRequestedSeqNr = s.requestedSeqNr + flowControlWindow / 2
              flightRecorder.consumerSentRequest(seqMsg.producerId, newRequestedSeqNr)
              context.log.debug(
                "Sending Request with confirmedSeqNr [{}], requestUpToSeqNr [{}].",
                seqNr,
                newRequestedSeqNr)
              s.producerController ! Request(confirmedSeqNr = seqNr, newRequestedSeqNr, resendLost, viaTimeout = false)
              startRetryTimer() // reset interval since Request was just sent
              newRequestedSeqNr
            } else {
              if (seqMsg.ack) {
                context.log.trace("Sending Ack seqNr [{}].", seqNr)
                s.producerController ! Ack(confirmedSeqNr = seqNr)
              }
              s.requestedSeqNr
            }

          if (s.stopping && stashBuffer.isEmpty) {
            context.log.debug("Stopped at seqNr [{}], after delivery of buffered messages.", seqNr)
            Behaviors.stopped { () =>
              // best effort to Ack latest confirmed when stopping
              s.producerController ! Ack(seqNr)
            }
          } else {
            stashBuffer.unstash(
              active(s.copy(confirmedSeqNr = seqNr, requestedSeqNr = newRequestedSeqNr)),
              1,
              scalaIdentityFunction)
          }

        case msg: SequencedMessage[A] =>
          flightRecorder.consumerReceivedPreviousInProgress(seqMsg.producerId, seqMsg.seqNr, stashBuffer.size + 1)
          if (msg.seqNr == seqMsg.seqNr && msg.producerController == seqMsg.producerController) {
            flightRecorder.consumerDuplicate(msg.producerId, seqMsg.seqNr + 1, msg.seqNr)
            context.log.debug("Received duplicate SequencedMessage seqNr [{}].", msg.seqNr)
          } else if (stashBuffer.isFull) {
            // possible that the stash is full if ProducerController resends unconfirmed (duplicates)
            // dropping them since they can be resent
            flightRecorder.consumerStashFull(seqMsg.producerId, seqMsg.seqNr)
            context.log.debug(
              "Received SequencedMessage seqNr [{}], discarding message because stash is full.",
              msg.seqNr)
          } else {
            if (context.log.isTraceEnabled())
              context.log.traceN(
                "Received SequencedMessage seqNr [{}], stashing while waiting for consumer to confirm [{}], stashed size [{}].",
                msg.seqNr,
                seqMsg.seqNr,
                stashBuffer.size + 1)
            stashBuffer.stash(msg)
          }
          Behaviors.same

        case Retry =>
          receiveRetry(s, () => waitingForConfirmation(retryRequest(s), seqMsg))

        case start: Start[A] =>
          start.deliverTo ! Delivery(seqMsg.message, context.self, seqMsg.producerId, seqMsg.seqNr)
          receiveStart(s, start, newState => waitingForConfirmation(newState, seqMsg))

        case ConsumerTerminated(c) =>
          receiveConsumerTerminated(c)

        case reg: RegisterToProducerController[A] =>
          receiveRegisterToProducerController(s, reg, newState => waitingForConfirmation(newState, seqMsg))

        case _: DeliverThenStop[_] =>
          receiveDeliverThenStop(s, newState => waitingForConfirmation(newState, seqMsg))

        case _: UnsealedInternalCommand =>
          Behaviors.unhandled
      }
      .receiveSignal {
        case (_, PostStop) => postStop(s)
      }
  }

  private def receiveRetry(s: State[A], nextBehavior: () => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    s.registering match {
      case None => nextBehavior()
      case Some(reg) =>
        reg ! ProducerController.RegisterConsumer(context.self)
        Behaviors.same
    }
  }

  private def receiveStart(
      s: State[A],
      start: Start[A],
      nextBehavior: State[A] => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    ConsumerControllerImpl.enforceLocalConsumer(start.deliverTo)
    if (start.deliverTo == s.consumer) {
      nextBehavior(s)
    } else {
      // if consumer is restarted it may send Start again
      context.unwatch(s.consumer)
      context.watchWith(start.deliverTo, ConsumerTerminated(start.deliverTo))
      nextBehavior(s.copy(consumer = start.deliverTo))
    }
  }

  private def receiveRegisterToProducerController(
      s: State[A],
      reg: RegisterToProducerController[A],
      nextBehavior: State[A] => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    if (reg.producerController != s.producerController) {
      context.log.debug2(
        "Register to new ProducerController [{}], previous was [{}].",
        reg.producerController,
        s.producerController)
      reg.producerController ! ProducerController.RegisterConsumer(context.self)
      nextBehavior(s.copy(registering = Some(reg.producerController)))
    } else {
      Behaviors.same
    }
  }

  private def receiveDeliverThenStop(
      s: State[A],
      nextBehavior: State[A] => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    if (stashBuffer.isEmpty && s.receivedSeqNr == s.confirmedSeqNr) {
      context.log.debug("Stopped at seqNr [{}], no buffered messages.", s.confirmedSeqNr)
      Behaviors.stopped
    } else {
      nextBehavior(s.copy(stopping = true))
    }
  }

  private def receiveConsumerTerminated(c: ActorRef[_]): Behavior[InternalCommand] = {
    context.log.debug("Consumer [{}] terminated.", c)
    Behaviors.stopped
  }

  private def receiveUnexpectedConfirmed(): Behavior[InternalCommand] = {
    context.log.warn("Received unexpected Confirmed from consumer.")
    Behaviors.unhandled
  }

  private def startRetryTimer(): Unit = {
    timers.startTimerWithFixedDelay(Retry, Retry, settings.resendInterval)
  }

  // in case the Request or the SequencedMessage triggering the Request is lost
  private def retryRequest(s: State[A]): State[A] = {
    if (s.producerController == context.system.deadLetters) {
      s
    } else {
      // TODO #28720 Maybe try to adjust the retry frequency. Maybe some exponential backoff and less need for it when
      //      SequenceMessage are arriving. On the other hand it might be too much overhead to reschedule of each
      //      incoming SequenceMessage.
      val newRequestedSeqNr = if (resendLost) s.requestedSeqNr else s.receivedSeqNr + flowControlWindow / 2
      flightRecorder.consumerSentRequest(s.producerId, newRequestedSeqNr)
      context.log.debug(
        "Retry sending Request with confirmedSeqNr [{}], requestUpToSeqNr [{}].",
        s.confirmedSeqNr,
        newRequestedSeqNr)
      // TODO #28720 maybe watch the producer to avoid sending retry Request to dead producer
      s.producerController ! Request(s.confirmedSeqNr, newRequestedSeqNr, resendLost, viaTimeout = true)
      s.copy(requestedSeqNr = newRequestedSeqNr)
    }
  }

  private def postStop(s: State[A]): Behavior[InternalCommand] = {
    // best effort to Ack latest confirmed when stopping
    s.producerController ! Ack(s.confirmedSeqNr)
    Behaviors.same
  }

}
