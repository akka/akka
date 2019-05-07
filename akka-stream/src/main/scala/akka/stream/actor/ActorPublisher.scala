/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.actor

import java.util.concurrent.ConcurrentHashMap

import akka.actor._
import akka.stream.impl.{ ReactiveStreamsCompliance, StreamSubscriptionTimeoutSupport }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import concurrent.duration.Duration
import concurrent.duration.FiniteDuration
import akka.stream.impl.CancelledSubscription
import akka.stream.impl.ReactiveStreamsCompliance._
import com.github.ghik.silencer.silent

@deprecated(
  "Use `akka.stream.stage.GraphStage` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.",
  since = "2.5.0")
object ActorPublisher {

  /**
   * Create a [[org.reactivestreams.Publisher]] backed by a [[ActorPublisher]] actor. It can be
   * attached to a [[org.reactivestreams.Subscriber]] or be used as an input source for a
   * [[akka.stream.scaladsl.Flow]].
   */
  def apply[T](ref: ActorRef): Publisher[T] = ActorPublisherImpl(ref)

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    final case class Subscribe(subscriber: Subscriber[Any])
        extends DeadLetterSuppression
        with NoSerializationVerificationNeeded

    sealed trait LifecycleState
    case object PreSubscriber extends LifecycleState
    case object Active extends LifecycleState
    case object Canceled extends LifecycleState
    case object Completed extends LifecycleState
    case object CompleteThenStop extends LifecycleState
    final case class ErrorEmitted(cause: Throwable, stop: Boolean) extends LifecycleState
  }
}

sealed abstract class ActorPublisherMessage extends DeadLetterSuppression

object ActorPublisherMessage {

  /**
   * This message is delivered to the [[ActorPublisher]] actor when the stream subscriber requests
   * more elements.
   * @param n number of requested elements
   */
  final case class Request(n: Long) extends ActorPublisherMessage with NoSerializationVerificationNeeded {
    private var processed = false

    /**
     * INTERNAL API: needed for stash support
     */
    private[akka] def markProcessed(): Unit = processed = true

    /**
     * INTERNAL API: needed for stash support
     */
    private[akka] def isProcessed(): Boolean = processed
  }

  /**
   * This message is delivered to the [[ActorPublisher]] actor when the stream subscriber cancels the
   * subscription.
   */
  final case object Cancel extends Cancel with NoSerializationVerificationNeeded
  sealed abstract class Cancel extends ActorPublisherMessage

  /**
   * Java API: get the singleton instance of the `Cancel` message
   */
  def cancelInstance = Cancel

  /**
   * This message is delivered to the [[ActorPublisher]] actor in order to signal the exceeding of an subscription timeout.
   * Once the actor receives this message, this publisher will already be in canceled state, thus the actor should clean-up and stop itself.
   */
  final case object SubscriptionTimeoutExceeded
      extends SubscriptionTimeoutExceeded
      with NoSerializationVerificationNeeded
  sealed abstract class SubscriptionTimeoutExceeded extends ActorPublisherMessage

  /**
   * Java API: get the singleton instance of the `SubscriptionTimeoutExceeded` message
   */
  def subscriptionTimeoutExceededInstance = SubscriptionTimeoutExceeded
}

/**
 * Extend/mixin this trait in your [[akka.actor.Actor]] to make it a
 * stream publisher that keeps track of the subscription life cycle and
 * requested elements.
 *
 * Create a [[org.reactivestreams.Publisher]] backed by this actor with Scala API [[ActorPublisher#apply]],
 * or Java API [[UntypedActorPublisher#create]] or Java API compatible with lambda expressions
 * [[AbstractActorPublisher#create]].
 *
 * It can be attached to a [[org.reactivestreams.Subscriber]] or be used as an input source for a
 * [[akka.stream.scaladsl.Flow]]. You can only attach one subscriber to this publisher.
 *
 * The life cycle state of the subscription is tracked with the following boolean members:
 * [[#isActive]], [[#isCompleted]], [[#isErrorEmitted]], and [[#isCanceled]].
 *
 * You send elements to the stream by calling [[#onNext]]. You are allowed to send as many
 * elements as have been requested by the stream subscriber. This amount can be inquired with
 * [[#totalDemand]]. It is only allowed to use `onNext` when `isActive` and `totalDemand > 0`,
 * otherwise `onNext` will throw `IllegalStateException`.
 *
 * When the stream subscriber requests more elements the [[ActorPublisher#Request]] message
 * is delivered to this actor, and you can act on that event. The [[#totalDemand]]
 * is updated automatically.
 *
 * When the stream subscriber cancels the subscription the [[ActorPublisher#Cancel]] message
 * is delivered to this actor. After that subsequent calls to `onNext` will be ignored.
 *
 * You can complete the stream by calling [[#onComplete]]. After that you are not allowed to
 * call [[#onNext]], [[#onError]] and [[#onComplete]].
 *
 * You can terminate the stream with failure by calling [[#onError]]. After that you are not allowed to
 * call [[#onNext]], [[#onError]] and [[#onComplete]].
 *
 * If you suspect that this [[ActorPublisher]] may never get subscribed to, you can override the [[#subscriptionTimeout]]
 * method to provide a timeout after which this Publisher should be considered canceled. The actor will be notified when
 * the timeout triggers via an [[akka.stream.actor.ActorPublisherMessage.SubscriptionTimeoutExceeded]] message and MUST then perform cleanup and stop itself.
 *
 * If the actor is stopped the stream will be completed, unless it was not already terminated with
 * failure, completed or canceled.
 *
 * @deprecated Use `akka.stream.stage.GraphStage` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.
 */
@deprecated(
  "Use `akka.stream.stage.GraphStage` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.",
  since = "2.5.0")
trait ActorPublisher[T] extends Actor {
  import ActorPublisher.Internal._
  import ActorPublisherMessage._
  import ReactiveStreamsCompliance._
  private val state = ActorPublisherState(context.system)
  private var subscriber: Subscriber[Any] = _
  private var demand = 0L
  private var lifecycleState: LifecycleState = PreSubscriber
  private var scheduledSubscriptionTimeout: Cancellable = StreamSubscriptionTimeoutSupport.NoopSubscriptionTimeout

  /**
   * Subscription timeout after which this actor will become Canceled and reject any incoming "late" subscriber.
   *
   * The actor will receive an [[SubscriptionTimeoutExceeded]] message upon which it
   * MUST react by performing all necessary cleanup and stopping itself.
   *
   * Use this feature in order to avoid leaking actors when you suspect that this Publisher may never get subscribed to by some Subscriber.
   */
  def subscriptionTimeout: Duration = Duration.Inf

  /**
   * The state when the publisher is active, i.e. before the subscriber is attached
   * and when an subscriber is attached. It is allowed to
   * call [[#onComplete]] and [[#onError]] in this state. It is
   * allowed to call [[#onNext]] in this state when [[#totalDemand]]
   * is greater than zero.
   */
  final def isActive: Boolean = lifecycleState == Active || lifecycleState == PreSubscriber

  /**
   * Total number of requested elements from the stream subscriber.
   * This actor automatically keeps tracks of this amount based on
   * incoming request messages and outgoing `onNext`.
   */
  final def totalDemand: Long = demand

  /**
   * The terminal state after calling [[#onComplete]]. It is not allowed to
   * call [[#onNext]], [[#onError]], and [[#onComplete]] in this state.
   */
  final def isCompleted: Boolean = lifecycleState == Completed

  /**
   * The terminal state after calling [[#onError]]. It is not allowed to
   * call [[#onNext]], [[#onError]], and [[#onComplete]] in this state.
   */
  final def isErrorEmitted: Boolean = lifecycleState.isInstanceOf[ErrorEmitted]

  /**
   * The state after the stream subscriber has canceled the subscription.
   * It is allowed to call [[#onNext]], [[#onError]], and [[#onComplete]] in
   * this state, but the calls will not perform anything.
   */
  final def isCanceled: Boolean = lifecycleState == Canceled

  /**
   * Send an element to the stream subscriber. You are allowed to send as many elements
   * as have been requested by the stream subscriber. This amount can be inquired with
   * [[#totalDemand]]. It is only allowed to use `onNext` when `isActive` and `totalDemand > 0`,
   * otherwise `onNext` will throw `IllegalStateException`.
   */
  def onNext(element: T): Unit = lifecycleState match {
    case Active | PreSubscriber =>
      if (demand > 0) {
        demand -= 1
        tryOnNext(subscriber, element)
      } else
        throw new IllegalStateException(
          "onNext is not allowed when the stream has not requested elements, totalDemand was 0")
    case _: ErrorEmitted =>
      throw new IllegalStateException("onNext must not be called after onError")
    case Completed | CompleteThenStop =>
      throw new IllegalStateException("onNext must not be called after onComplete")
    case Canceled => // drop
  }

  /**
   * Complete the stream. After that you are not allowed to
   * call [[#onNext]], [[#onError]] and [[#onComplete]].
   */
  def onComplete(): Unit = lifecycleState match {
    case Active | PreSubscriber =>
      lifecycleState = Completed
      if (subscriber ne null) // otherwise onComplete will be called when the subscription arrives
        try tryOnComplete(subscriber)
        finally subscriber = null
    case Completed | CompleteThenStop =>
      throw new IllegalStateException("onComplete must only be called once")
    case _: ErrorEmitted =>
      throw new IllegalStateException("onComplete must not be called after onError")
    case Canceled => // drop
  }

  /**
   * Complete the stream. After that you are not allowed to
   * call [[#onNext]], [[#onError]] and [[#onComplete]].
   *
   * After signaling completion the Actor will then stop itself as it has completed the protocol.
   * When [[#onComplete]] is called before any [[Subscriber]] has had the chance to subscribe
   * to this [[ActorPublisher]] the completion signal (and therefore stopping of the Actor as well)
   * will be delayed until such [[Subscriber]] arrives.
   */
  def onCompleteThenStop(): Unit = lifecycleState match {
    case Active | PreSubscriber =>
      lifecycleState = CompleteThenStop
      if (subscriber ne null) // otherwise onComplete will be called when the subscription arrives
        try tryOnComplete(subscriber)
        finally context.stop(self)
    case _ => onComplete()
  }

  /**
   * Terminate the stream with failure. After that you are not allowed to
   * call [[#onNext]], [[#onError]] and [[#onComplete]].
   */
  def onError(cause: Throwable): Unit = lifecycleState match {
    case Active | PreSubscriber =>
      lifecycleState = ErrorEmitted(cause, stop = false)
      if (subscriber ne null) // otherwise onError will be called when the subscription arrives
        try tryOnError(subscriber, cause)
        finally subscriber = null
    case _: ErrorEmitted =>
      throw new IllegalStateException("onError must only be called once")
    case Completed | CompleteThenStop =>
      throw new IllegalStateException("onError must not be called after onComplete")
    case Canceled => // drop
  }

  /**
   * Terminate the stream with failure. After that you are not allowed to
   * call [[#onNext]], [[#onError]] and [[#onComplete]].
   *
   * After signaling the Error the Actor will then stop itself as it has completed the protocol.
   * When [[#onError]] is called before any [[Subscriber]] has had the chance to subscribe
   * to this [[ActorPublisher]] the error signal (and therefore stopping of the Actor as well)
   * will be delayed until such [[Subscriber]] arrives.
   */
  def onErrorThenStop(cause: Throwable): Unit = lifecycleState match {
    case Active | PreSubscriber =>
      lifecycleState = ErrorEmitted(cause, stop = true)
      if (subscriber ne null) // otherwise onError will be called when the subscription arrives
        try tryOnError(subscriber, cause)
        finally context.stop(self)
    case _ => onError(cause)
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case req @ Request(n) =>
      if (req.isProcessed()) {
        // it's an unstashed Request, demand is already handled
        super.aroundReceive(receive, req)
      } else {
        if (n < 1) {
          if (lifecycleState == Active)
            onError(numberOfElementsInRequestMustBePositiveException)
        } else {
          demand += n
          if (demand < 0)
            demand = Long.MaxValue // Long overflow, Reactive Streams Spec 3:17: effectively unbounded
          req.markProcessed()
          super.aroundReceive(receive, req)
        }
      }

    case Subscribe(sub: Subscriber[_]) =>
      lifecycleState match {
        case PreSubscriber =>
          scheduledSubscriptionTimeout.cancel()
          subscriber = sub
          lifecycleState = Active
          tryOnSubscribe(sub, new ActorPublisherSubscription(self))
        case ErrorEmitted(cause, stop) =>
          if (stop) context.stop(self)
          tryOnSubscribe(sub, CancelledSubscription)
          tryOnError(sub, cause)
        case Completed =>
          tryOnSubscribe(sub, CancelledSubscription)
          tryOnComplete(sub)
        case CompleteThenStop =>
          context.stop(self)
          tryOnSubscribe(sub, CancelledSubscription)
          tryOnComplete(sub)
        case Active | Canceled =>
          if (subscriber eq sub)
            rejectDuplicateSubscriber(sub)
          else
            rejectAdditionalSubscriber(sub, "ActorPublisher")
      }

    case Cancel =>
      if (lifecycleState != Canceled) {
        // possible to receive again in case of stash
        cancelSelf()
        super.aroundReceive(receive, msg)
      }

    case SubscriptionTimeoutExceeded =>
      if (!scheduledSubscriptionTimeout.isCancelled) {
        cancelSelf()
        super.aroundReceive(receive, msg)
      }

    case _ =>
      super.aroundReceive(receive, msg)
  }

  private def cancelSelf(): Unit = {
    lifecycleState = Canceled
    demand = 0
    subscriber = null
  }

  /**
   * INTERNAL API
   */
  override protected[akka] def aroundPreStart(): Unit = {
    super.aroundPreStart()
    import context.dispatcher

    subscriptionTimeout match {
      case timeout: FiniteDuration =>
        scheduledSubscriptionTimeout = context.system.scheduler.scheduleOnce(timeout, self, SubscriptionTimeoutExceeded)
      case _ =>
      // ignore...
    }
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // some state must survive restart
    state.set(self, ActorPublisherState.State(Option(subscriber), demand, lifecycleState))
    super.aroundPreRestart(reason, message)
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPostRestart(reason: Throwable): Unit = {
    state.get(self).foreach { s =>
      // restore previous state
      subscriber = s.subscriber.orNull
      demand = s.demand
      lifecycleState = s.lifecycleState
    }
    state.remove(self)
    super.aroundPostRestart(reason)
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPostStop(): Unit = {
    state.remove(self)
    try if (lifecycleState == Active) tryOnComplete(subscriber)
    finally super.aroundPostStop()
  }

}

/**
 * INTERNAL API
 */
@silent
private[akka] final case class ActorPublisherImpl[T](ref: ActorRef) extends Publisher[T] {
  import ActorPublisher.Internal._

  override def subscribe(sub: Subscriber[_ >: T]): Unit = {
    requireNonNullSubscriber(sub)
    ref ! Subscribe(sub.asInstanceOf[Subscriber[Any]])
  }
}

/**
 * INTERNAL API
 */
private[akka] class ActorPublisherSubscription[T](ref: ActorRef) extends Subscription {
  import ActorPublisherMessage._

  override def request(n: Long): Unit = ref ! Request(n)
  override def cancel(): Unit = ref ! Cancel
}

/**
 * INTERNAL API
 * Some state must survive restarts.
 */
private[akka] object ActorPublisherState extends ExtensionId[ActorPublisherState] with ExtensionIdProvider {
  import ActorPublisher.Internal.LifecycleState

  override def get(system: ActorSystem): ActorPublisherState = super.get(system)

  override def lookup() = ActorPublisherState

  override def createExtension(system: ExtendedActorSystem): ActorPublisherState =
    new ActorPublisherState

  final case class State(subscriber: Option[Subscriber[Any]], demand: Long, lifecycleState: LifecycleState)

}

/**
 * INTERNAL API
 */
private[akka] class ActorPublisherState extends Extension {
  import ActorPublisherState.State
  private val state = new ConcurrentHashMap[ActorRef, State]

  def get(ref: ActorRef): Option[State] = Option(state.get(ref))

  def set(ref: ActorRef, s: State): Unit = state.put(ref, s)

  def remove(ref: ActorRef): Unit = state.remove(ref)
}
