/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.delivery.internal

import java.util.concurrent.TimeoutException

import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success

import akka.actor.DeadLetterSuppression
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.delivery.ConsumerController.SequencedMessage
import akka.actor.typed.delivery.DurableProducerQueue
import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.internal.ActorFlightRecorder
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.TimerScheduler
import akka.util.Timeout

/**
 * INTERNAL API
 *
 * ==== Design notes ====
 *
 * The producer will start the flow by sending a [[ProducerController.Start]] message to the `ProducerController` with
 * message adapter reference to convert [[ProducerController.RequestNext]] message.
 * The `ProducerController` sends `RequestNext` to the producer, which is then allowed to send one message to
 * the `ProducerController`.
 *
 * The producer and `ProducerController` are supposed to be local so that these messages are fast and not lost.
 *
 * The `ProducerController` sends the first message to the `ConsumerController` without waiting for
 * a `Request` from the `ConsumerController`. The main reason for this is that when used with
 * Cluster Sharding the first message will typically create the `ConsumerController`. It's
 * also a way to connect the ProducerController and ConsumerController in a dynamic way, for
 * example when the ProducerController is replaced.
 *
 * When the first message is received by the `ConsumerController` it sends back the initial `Request`,
 * with demand of how many messages it can accept.
 *
 * Apart from the first message the `ProducerController` will not send more messages than requested
 * by the `ConsumerController`.
 *
 * When there is demand from the consumer side the `ProducerController` sends `RequestNext` to the
 * actual producer, which is then allowed to send one more message.
 *
 * Each message is wrapped by the `ProducerController` in [[ConsumerController.SequencedMessage]] with
 * a monotonically increasing sequence number without gaps, starting at 1.
 *
 * In other words, the "request" protocol to the application producer and consumer is one-by-one, but
 * between the `ProducerController` and `ConsumerController` it's window of messages in flight.
 *
 * The `Request` message also contains a `confirmedSeqNr` that is the acknowledgement
 * from the consumer that it has received and processed all messages up to that sequence number.
 *
 * The `ConsumerController` will send [[ProducerControllerImpl.Resend]] if a lost message is detected
 * and then the `ProducerController` will resend all messages from that sequence number. The producer keeps
 * unconfirmed messages in a buffer to be able to resend them. The buffer size is limited
 * by the request window size.
 *
 * The resending is optional, and the `ConsumerController` can be started with `resendLost=false`
 * to ignore lost messages, and then the `ProducerController` will not buffer unconfirmed messages.
 * In that mode it provides only flow control but no reliable delivery.
 */
object ProducerControllerImpl {

  import ProducerController.Command
  import ProducerController.RegisterConsumer
  import ProducerController.RequestNext
  import ProducerController.SeqNr
  import ProducerController.Start

  sealed trait InternalCommand

  /** For commands defined in public ProducerController */
  trait UnsealedInternalCommand extends InternalCommand

  final case class Request(confirmedSeqNr: SeqNr, requestUpToSeqNr: SeqNr, supportResend: Boolean, viaTimeout: Boolean)
      extends InternalCommand
      with DeliverySerializable
      with DeadLetterSuppression {
    require(
      confirmedSeqNr <= requestUpToSeqNr,
      s"confirmedSeqNr [$confirmedSeqNr] should be <= requestUpToSeqNr [$requestUpToSeqNr]")
  }
  final case class Resend(fromSeqNr: SeqNr) extends InternalCommand with DeliverySerializable with DeadLetterSuppression
  final case class Ack(confirmedSeqNr: SeqNr)
      extends InternalCommand
      with DeliverySerializable
      with DeadLetterSuppression

  private case class Msg[A](msg: A) extends InternalCommand
  private case object ResendFirst extends InternalCommand
  case object ResendFirstUnconfirmed extends InternalCommand

  private case class LoadStateReply[A](state: DurableProducerQueue.State[A]) extends InternalCommand
  private case class LoadStateFailed(attempt: Int) extends InternalCommand
  private case class StoreMessageSentReply(ack: DurableProducerQueue.StoreMessageSentAck)
  private case class StoreMessageSentFailed[A](messageSent: DurableProducerQueue.MessageSent[A], attempt: Int)
      extends InternalCommand
  private case object DurableQueueTerminated extends InternalCommand

  private case class StoreMessageSentCompleted[A](messageSent: DurableProducerQueue.MessageSent[A])
      extends InternalCommand

  private final case class State[A](
      requested: Boolean,
      currentSeqNr: SeqNr,
      confirmedSeqNr: SeqNr,
      requestedSeqNr: SeqNr,
      replyAfterStore: Map[SeqNr, ActorRef[SeqNr]],
      supportResend: Boolean,
      unconfirmed: Vector[ConsumerController.SequencedMessage[A]],
      firstSeqNr: SeqNr,
      producer: ActorRef[ProducerController.RequestNext[A]],
      send: ConsumerController.SequencedMessage[A] => Unit)

  def apply[A: ClassTag](
      producerId: String,
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: ProducerController.Settings): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        ActorFlightRecorder(context.system).delivery.producerCreated(producerId, context.self.path)
        Behaviors.withMdc(staticMdc = Map("producerId" -> producerId)) {
          context.setLoggerName("akka.actor.typed.delivery.ProducerController")
          val durableQueue = askLoadState(context, durableQueueBehavior, settings)
          waitingForInitialization[A](
            context,
            None,
            None,
            durableQueue,
            settings,
            createInitialState(durableQueue.nonEmpty)) { (producer, consumerController, loadedState) =>
            val send: ConsumerController.SequencedMessage[A] => Unit = consumerController ! _
            becomeActive(
              producerId,
              durableQueue,
              settings,
              createState(context.self, producerId, send, producer, loadedState))
          }
        }
      }
      .narrow
  }

  /**
   * For custom `send` function. For example used with Sharding where the message must be wrapped in
   * `ShardingEnvelope(SequencedMessage(msg))`.
   */
  def apply[A: ClassTag](
      producerId: String,
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: ProducerController.Settings,
      send: ConsumerController.SequencedMessage[A] => Unit): Behavior[Command[A]] = {
    Behaviors
      .setup[InternalCommand] { context =>
        ActorFlightRecorder(context.system).delivery.producerCreated(producerId, context.self.path)
        Behaviors.withMdc(staticMdc = Map("producerId" -> producerId)) {
          context.setLoggerName("akka.actor.typed.delivery.ProducerController")
          val durableQueue = askLoadState(context, durableQueueBehavior, settings)
          // ConsumerController not used here
          waitingForInitialization[A](
            context,
            None,
            consumerController = Some(context.system.deadLetters),
            durableQueue,
            settings,
            createInitialState(durableQueue.nonEmpty)) { (producer, _, loadedState) =>
            becomeActive(
              producerId,
              durableQueue,
              settings,
              createState(context.self, producerId, send, producer, loadedState))
          }
        }
      }
      .narrow
  }

  private def askLoadState[A: ClassTag](
      context: ActorContext[InternalCommand],
      durableQueueBehavior: Option[Behavior[DurableProducerQueue.Command[A]]],
      settings: ProducerController.Settings): Option[ActorRef[DurableProducerQueue.Command[A]]] = {

    durableQueueBehavior.map { b =>
      val ref = context.spawn(b, "durable", DispatcherSelector.sameAsParent())
      context.watchWith(ref, DurableQueueTerminated)
      askLoadState(context, Some(ref), settings, attempt = 1)
      ref
    }
  }

  private def askLoadState[A: ClassTag](
      context: ActorContext[InternalCommand],
      durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
      settings: ProducerController.Settings,
      attempt: Int): Unit = {
    implicit val loadTimeout: Timeout = settings.durableQueueRequestTimeout
    durableQueue.foreach { ref =>
      context.ask[DurableProducerQueue.LoadState[A], DurableProducerQueue.State[A]](
        ref,
        askReplyTo => DurableProducerQueue.LoadState[A](askReplyTo)) {
        case Success(s) => LoadStateReply(s)
        case Failure(_) => LoadStateFailed(attempt) // timeout
      }
    }
  }

  private def createInitialState[A: ClassTag](hasDurableQueue: Boolean) = {
    if (hasDurableQueue) None else Some(DurableProducerQueue.State.empty[A])
  }

  private def createState[A: ClassTag](
      self: ActorRef[InternalCommand],
      producerId: String,
      send: SequencedMessage[A] => Unit,
      producer: ActorRef[RequestNext[A]],
      loadedState: DurableProducerQueue.State[A]): State[A] = {
    val unconfirmed = loadedState.unconfirmed.toVector.zipWithIndex.map {
      case (u, i) => SequencedMessage[A](producerId, u.seqNr, u.message, i == 0, u.ack)(self)
    }
    State(
      requested = false,
      currentSeqNr = loadedState.currentSeqNr,
      confirmedSeqNr = loadedState.highestConfirmedSeqNr,
      requestedSeqNr = 1L,
      replyAfterStore = Map.empty,
      supportResend = true,
      unconfirmed = unconfirmed,
      firstSeqNr = loadedState.highestConfirmedSeqNr + 1,
      producer,
      send)
  }

  private def waitingForInitialization[A: ClassTag](
      context: ActorContext[InternalCommand],
      producer: Option[ActorRef[RequestNext[A]]],
      consumerController: Option[ActorRef[ConsumerController.Command[A]]],
      durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
      settings: ProducerController.Settings,
      initialState: Option[DurableProducerQueue.State[A]])(
      thenBecomeActive: (
          ActorRef[RequestNext[A]],
          ActorRef[ConsumerController.Command[A]],
          DurableProducerQueue.State[A]) => Behavior[InternalCommand]): Behavior[InternalCommand] = {
    Behaviors.receiveMessagePartial[InternalCommand] {
      case RegisterConsumer(c: ActorRef[ConsumerController.Command[A]] @unchecked) =>
        (producer, initialState) match {
          case (Some(p), Some(s)) => thenBecomeActive(p, c, s)
          case (_, _) =>
            waitingForInitialization(context, producer, Some(c), durableQueue, settings, initialState)(thenBecomeActive)
        }
      case start: Start[A] @unchecked =>
        (consumerController, initialState) match {
          case (Some(c), Some(s)) => thenBecomeActive(start.producer, c, s)
          case (_, _) =>
            waitingForInitialization(
              context,
              Some(start.producer),
              consumerController,
              durableQueue,
              settings,
              initialState)(thenBecomeActive)
        }
      case load: LoadStateReply[A] @unchecked =>
        (producer, consumerController) match {
          case (Some(p), Some(c)) => thenBecomeActive(p, c, load.state)
          case (_, _) =>
            waitingForInitialization(context, producer, consumerController, durableQueue, settings, Some(load.state))(
              thenBecomeActive)
        }
      case LoadStateFailed(attempt) =>
        if (attempt >= settings.durableQueueRetryAttempts) {
          val errorMessage = s"LoadState failed after [$attempt] attempts, giving up."
          context.log.error(errorMessage)
          throw new TimeoutException(errorMessage)
        } else {
          context.log.warn(
            "LoadState failed, attempt [{}] of [{}], retrying.",
            attempt,
            settings.durableQueueRetryAttempts)
          // retry
          askLoadState(context, durableQueue, settings, attempt + 1)
          Behaviors.same
        }
      case DurableQueueTerminated =>
        throw new IllegalStateException("DurableQueue was unexpectedly terminated.")
    }
  }

  private def becomeActive[A: ClassTag](
      producerId: String,
      durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
      settings: ProducerController.Settings,
      state: State[A]): Behavior[InternalCommand] = {

    Behaviors.setup { context =>
      val flightRecorder = ActorFlightRecorder(context.system).delivery
      flightRecorder.producerStarted(producerId, context.self.path)
      Behaviors.withTimers { timers =>
        val msgAdapter: ActorRef[A] = context.messageAdapter(msg => Msg(msg))
        val requested =
          if (state.unconfirmed.isEmpty) {
            flightRecorder.producerRequestNext(producerId, 1L, 0)
            state.producer ! RequestNext(producerId, 1L, 0L, msgAdapter, context.self)
            true
          } else {
            context.log.debug("Starting with [{}] unconfirmed.", state.unconfirmed.size)
            context.self ! ResendFirst
            false
          }
        new ProducerControllerImpl[A](context, producerId, durableQueue, settings, msgAdapter, timers)
          .active(state.copy(requested = requested))
      }
    }
  }

  def enforceLocalProducer(ref: ActorRef[_]): Unit = {
    if (ref.path.address.hasGlobalScope)
      throw new IllegalArgumentException(s"Consumer [$ref] should be local.")
  }

}

private class ProducerControllerImpl[A: ClassTag](
    context: ActorContext[ProducerControllerImpl.InternalCommand],
    producerId: String,
    durableQueue: Option[ActorRef[DurableProducerQueue.Command[A]]],
    settings: ProducerController.Settings,
    msgAdapter: ActorRef[A],
    timers: TimerScheduler[ProducerControllerImpl.InternalCommand]) {
  import ConsumerController.SequencedMessage
  import DurableProducerQueue.MessageSent
  import DurableProducerQueue.NoQualifier
  import DurableProducerQueue.StoreMessageConfirmed
  import DurableProducerQueue.StoreMessageSent
  import DurableProducerQueue.StoreMessageSentAck
  import ProducerController.MessageWithConfirmation
  import ProducerController.RegisterConsumer
  import ProducerController.RequestNext
  import ProducerController.SeqNr
  import ProducerController.Start
  import ProducerControllerImpl._

  private val flightRecorder = ActorFlightRecorder(context.system).delivery
  private val traceEnabled = context.log.isTraceEnabled
  // for the durableQueue StoreMessageSent ask
  private implicit val askTimeout: Timeout = settings.durableQueueRequestTimeout

  private def active(s: State[A]): Behavior[InternalCommand] = {

    def onMsg(m: A, newReplyAfterStore: Map[SeqNr, ActorRef[SeqNr]], ack: Boolean): Behavior[InternalCommand] = {
      checkOnMsgRequestedState()
      if (traceEnabled)
        context.log.trace("Sending [{}] with seqNr [{}].", m.getClass.getName, s.currentSeqNr)
      val seqMsg = SequencedMessage(producerId, s.currentSeqNr, m, s.currentSeqNr == s.firstSeqNr, ack)(context.self)
      val newUnconfirmed =
        if (s.supportResend) s.unconfirmed :+ seqMsg
        else Vector.empty // no resending, no need to keep unconfirmed

      if (s.currentSeqNr == s.firstSeqNr)
        timers.startTimerWithFixedDelay(ResendFirst, delay = settings.durableQueueResendFirstInterval)

      flightRecorder.producerSent(producerId, seqMsg.seqNr)
      s.send(seqMsg)
      val newRequested =
        if (s.currentSeqNr == s.requestedSeqNr) {
          flightRecorder.producerWaitingForRequest(producerId, s.currentSeqNr)
          false
        } else {
          flightRecorder.producerRequestNext(producerId, s.currentSeqNr + 1, s.confirmedSeqNr)
          s.producer ! RequestNext(producerId, s.currentSeqNr + 1, s.confirmedSeqNr, msgAdapter, context.self)
          true
        }
      active(
        s.copy(
          requested = newRequested,
          currentSeqNr = s.currentSeqNr + 1,
          replyAfterStore = newReplyAfterStore,
          unconfirmed = newUnconfirmed))
    }

    def checkOnMsgRequestedState(): Unit = {
      if (!s.requested || s.currentSeqNr > s.requestedSeqNr) {
        throw new IllegalStateException(
          s"Unexpected Msg when no demand, requested ${s.requested}, " +
          s"requestedSeqNr ${s.requestedSeqNr}, currentSeqNr ${s.currentSeqNr}")
      }
    }

    def receiveRequest(
        newConfirmedSeqNr: SeqNr,
        newRequestedSeqNr: SeqNr,
        supportResend: Boolean,
        viaTimeout: Boolean): Behavior[InternalCommand] = {
      flightRecorder.producerReceivedRequest(producerId, newRequestedSeqNr, newConfirmedSeqNr)
      context.log.debugN(
        "Received Request, confirmed [{}], requested [{}], current [{}]",
        newConfirmedSeqNr,
        newRequestedSeqNr,
        s.currentSeqNr)

      val stateAfterAck = onAck(newConfirmedSeqNr)

      val newUnconfirmed =
        if (supportResend) stateAfterAck.unconfirmed
        else Vector.empty

      if ((viaTimeout || newConfirmedSeqNr == s.firstSeqNr) && supportResend) {
        // the last message was lost and no more message was sent that would trigger Resend
        resendUnconfirmed(newUnconfirmed)
      }

      // when supportResend=false the requestedSeqNr window must be expanded if all sent messages were lost
      val newRequestedSeqNr2 =
        if (!supportResend && newRequestedSeqNr <= stateAfterAck.currentSeqNr)
          stateAfterAck.currentSeqNr + (newRequestedSeqNr - newConfirmedSeqNr)
        else
          newRequestedSeqNr
      if (newRequestedSeqNr2 != newRequestedSeqNr)
        context.log.debugN(
          "Expanded requestedSeqNr from [{}] to [{}], because current [{}] and all were probably lost",
          newRequestedSeqNr,
          newRequestedSeqNr2,
          stateAfterAck.currentSeqNr)

      if (newRequestedSeqNr2 > s.requestedSeqNr) {
        if (!s.requested && (newRequestedSeqNr2 - s.currentSeqNr) > 0) {
          flightRecorder.producerRequestNext(producerId, s.currentSeqNr, newConfirmedSeqNr)
          s.producer ! RequestNext(producerId, s.currentSeqNr, newConfirmedSeqNr, msgAdapter, context.self)
        }
        active(
          stateAfterAck.copy(
            requested = true,
            requestedSeqNr = newRequestedSeqNr2,
            supportResend = supportResend,
            unconfirmed = newUnconfirmed))
      } else {
        active(stateAfterAck.copy(supportResend = supportResend, unconfirmed = newUnconfirmed))
      }
    }

    def receiveAck(newConfirmedSeqNr: SeqNr): Behavior[InternalCommand] = {
      if (traceEnabled)
        context.log.trace2("Received Ack, confirmed [{}], current [{}].", newConfirmedSeqNr, s.currentSeqNr)
      val stateAfterAck = onAck(newConfirmedSeqNr)
      if (newConfirmedSeqNr == s.firstSeqNr && stateAfterAck.unconfirmed.nonEmpty) {
        resendUnconfirmed(stateAfterAck.unconfirmed)
      }
      active(stateAfterAck)
    }

    def onAck(newConfirmedSeqNr: SeqNr): State[A] = {
      val (replies, newReplyAfterStore) = s.replyAfterStore.partition { case (seqNr, _) => seqNr <= newConfirmedSeqNr }
      if (replies.nonEmpty && traceEnabled)
        context.log.trace("Sending confirmation replies from [{}] to [{}].", replies.head._1, replies.last._1)
      replies.foreach {
        case (seqNr, replyTo) => replyTo ! seqNr
      }

      val newUnconfirmed =
        if (s.supportResend) s.unconfirmed.dropWhile(_.seqNr <= newConfirmedSeqNr)
        else Vector.empty

      if (newConfirmedSeqNr == s.firstSeqNr)
        timers.cancel(ResendFirst)

      val newMaxConfirmedSeqNr = math.max(s.confirmedSeqNr, newConfirmedSeqNr)

      durableQueue.foreach { d =>
        // Storing the confirmedSeqNr can be "write behind", at-least-once delivery
        // TODO #28721 to reduce number of writes, consider to only StoreMessageConfirmed for the Request messages and not for each Ack
        if (newMaxConfirmedSeqNr != s.confirmedSeqNr)
          d ! StoreMessageConfirmed(newMaxConfirmedSeqNr, NoQualifier, System.currentTimeMillis())
      }

      s.copy(confirmedSeqNr = newMaxConfirmedSeqNr, replyAfterStore = newReplyAfterStore, unconfirmed = newUnconfirmed)
    }

    def receiveStoreMessageSentCompleted(seqNr: SeqNr, m: A, ack: Boolean) = {
      if (seqNr != s.currentSeqNr)
        throw new IllegalStateException(s"currentSeqNr [${s.currentSeqNr}] not matching stored seqNr [$seqNr]")

      s.replyAfterStore.get(seqNr).foreach { replyTo =>
        if (traceEnabled)
          context.log.trace("Sending confirmation reply to [{}] after storage.", seqNr)
        replyTo ! seqNr
      }
      val newReplyAfterStore = s.replyAfterStore - seqNr

      onMsg(m, newReplyAfterStore, ack)
    }

    def receiveResend(fromSeqNr: SeqNr): Behavior[InternalCommand] = {
      flightRecorder.producerReceivedResend(producerId, fromSeqNr)
      val newUnconfirmed =
        if (fromSeqNr == 0 && s.unconfirmed.nonEmpty)
          s.unconfirmed.head.asFirst +: s.unconfirmed.tail
        else
          s.unconfirmed.dropWhile(_.seqNr < fromSeqNr)
      resendUnconfirmed(newUnconfirmed)
      active(s.copy(unconfirmed = newUnconfirmed))
    }

    def resendUnconfirmed(newUnconfirmed: Vector[SequencedMessage[A]]): Unit = {
      if (newUnconfirmed.nonEmpty) {
        val fromSeqNr = newUnconfirmed.head.seqNr
        val toSeqNr = newUnconfirmed.last.seqNr
        flightRecorder.producerResentUnconfirmed(producerId, fromSeqNr, toSeqNr)
        context.log.debug("Resending [{} - {}].", fromSeqNr, toSeqNr)
        newUnconfirmed.foreach(s.send)
      }
    }

    def receiveResendFirstUnconfirmed(): Behavior[InternalCommand] = {
      if (s.unconfirmed.nonEmpty) {
        flightRecorder.producerResentFirstUnconfirmed(producerId, s.unconfirmed.head.seqNr)
        context.log.debug("Resending first unconfirmed [{}].", s.unconfirmed.head.seqNr)
        s.send(s.unconfirmed.head)
      }
      Behaviors.same
    }

    def receiveResendFirst(): Behavior[InternalCommand] = {
      if (s.unconfirmed.nonEmpty && s.unconfirmed.head.seqNr == s.firstSeqNr) {
        flightRecorder.producerResentFirst(producerId, s.firstSeqNr)
        context.log.debug("Resending first, [{}].", s.firstSeqNr)
        s.send(s.unconfirmed.head.asFirst)
      } else {
        if (s.currentSeqNr > s.firstSeqNr)
          timers.cancel(ResendFirst)
      }
      Behaviors.same
    }

    def receiveStart(start: Start[A]): Behavior[InternalCommand] = {
      ProducerControllerImpl.enforceLocalProducer(start.producer)
      context.log.debug("Register new Producer [{}], currentSeqNr [{}].", start.producer, s.currentSeqNr)
      if (s.requested) {
        flightRecorder.producerRequestNext(producerId, s.currentSeqNr, s.confirmedSeqNr)
        start.producer ! RequestNext(producerId, s.currentSeqNr, s.confirmedSeqNr, msgAdapter, context.self)
      }
      active(s.copy(producer = start.producer))
    }

    def receiveRegisterConsumer(
        consumerController: ActorRef[ConsumerController.Command[A]]): Behavior[InternalCommand] = {
      val newFirstSeqNr =
        if (s.unconfirmed.isEmpty) s.currentSeqNr
        else s.unconfirmed.head.seqNr
      context.log.debug(
        "Register new ConsumerController [{}], starting with seqNr [{}].",
        consumerController,
        newFirstSeqNr)
      if (s.unconfirmed.nonEmpty) {
        timers.startTimerWithFixedDelay(ResendFirst, delay = settings.durableQueueResendFirstInterval)
        context.self ! ResendFirst
      }
      // update the send function
      val newSend = consumerController ! _
      active(s.copy(firstSeqNr = newFirstSeqNr, send = newSend))
    }

    Behaviors.receiveMessage {
      case MessageWithConfirmation(m: A, replyTo) =>
        flightRecorder.producerReceived(producerId, s.currentSeqNr)
        val newReplyAfterStore = s.replyAfterStore.updated(s.currentSeqNr, replyTo)
        if (durableQueue.isEmpty) {
          onMsg(m, newReplyAfterStore, ack = true)
        } else {
          storeMessageSent(
            MessageSent(s.currentSeqNr, m, ack = true, NoQualifier, System.currentTimeMillis()),
            attempt = 1)
          active(s.copy(replyAfterStore = newReplyAfterStore))
        }

      case Msg(m: A) =>
        flightRecorder.producerReceived(producerId, s.currentSeqNr)
        if (durableQueue.isEmpty) {
          onMsg(m, s.replyAfterStore, ack = false)
        } else {
          storeMessageSent(
            MessageSent(s.currentSeqNr, m, ack = false, NoQualifier, System.currentTimeMillis()),
            attempt = 1)
          Behaviors.same
        }

      case StoreMessageSentCompleted(MessageSent(seqNr, m: A, ack, NoQualifier, _)) =>
        receiveStoreMessageSentCompleted(seqNr, m, ack)

      case f: StoreMessageSentFailed[A] =>
        receiveStoreMessageSentFailed(f)

      case Request(newConfirmedSeqNr, newRequestedSeqNr, supportResend, viaTimeout) =>
        receiveRequest(newConfirmedSeqNr, newRequestedSeqNr, supportResend, viaTimeout)

      case Ack(newConfirmedSeqNr) =>
        receiveAck(newConfirmedSeqNr)

      case Resend(fromSeqNr) =>
        receiveResend(fromSeqNr)

      case ResendFirst =>
        receiveResendFirst()

      case ResendFirstUnconfirmed =>
        receiveResendFirstUnconfirmed()

      case start: Start[A] =>
        receiveStart(start)

      case RegisterConsumer(consumerController: ActorRef[ConsumerController.Command[A]] @unchecked) =>
        receiveRegisterConsumer(consumerController)

      case DurableQueueTerminated =>
        throw new IllegalStateException("DurableQueue was unexpectedly terminated.")
    }
  }

  private def receiveStoreMessageSentFailed(f: StoreMessageSentFailed[A]): Behavior[InternalCommand] = {
    if (f.attempt >= settings.durableQueueRetryAttempts) {
      val errorMessage =
        s"StoreMessageSentFailed seqNr [${f.messageSent.seqNr}] failed after [${f.attempt}] attempts, giving up."
      context.log.error(errorMessage)
      throw new TimeoutException(errorMessage)
    } else {
      context.log.warnN(
        "StoreMessageSent seqNr [{}] failed, attempt [{}] of [{}], retrying.",
        f.messageSent.seqNr,
        f.attempt,
        settings.durableQueueRetryAttempts)
      // retry
      storeMessageSent(f.messageSent, attempt = f.attempt + 1)
      Behaviors.same
    }
  }

  private def storeMessageSent(messageSent: MessageSent[A], attempt: Int): Unit = {
    context.ask[StoreMessageSent[A], StoreMessageSentAck](
      durableQueue.get,
      askReplyTo => StoreMessageSent(messageSent, askReplyTo)) {
      case Success(_) => StoreMessageSentCompleted(messageSent)
      case Failure(_) => StoreMessageSentFailed(messageSent, attempt) // timeout
    }
  }
}
