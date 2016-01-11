/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.actor

import java.util.concurrent.ConcurrentHashMap
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

object ActorPublisher {

  /**
   * Create a [[org.reactivestreams.Publisher]] backed by a [[ActorPublisher]] actor. It can be
   * attached to a [[org.reactivestreams.Subscriber]] or be used as an input source for a
   * [[akka.stream.Flow]].
   */
  def apply[T](ref: ActorRef): Publisher[T] = ActorPublisherImpl(ref)

  /**
   * This message is delivered to the [[ActorPublisher]] actor when the stream subscriber requests
   * more elements.
   */
  @SerialVersionUID(1L) case class Request(elements: Int)

  /**
   * This message is delivered to the [[ActorPublisher]] actor when the stream subscriber cancels the
   * subscription.
   */
  @SerialVersionUID(1L) case object Cancel

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    case class Subscribe(subscriber: Subscriber[Any])

    sealed trait LifecycleState
    case object PreSubscriber extends LifecycleState
    case object Active extends LifecycleState
    case object Canceled extends LifecycleState
    case object Completed extends LifecycleState
    case class ErrorEmitted(cause: Throwable) extends LifecycleState
  }
}

/**
 * Extend/mixin this trait in your [[akka.actor.Actor]] to make it a
 * stream publisher that keeps track of the subscription life cycle and
 * requested elements.
 *
 * Create a [[org.reactivestreams.Publisher]] backed by this actor with [[ActorPublisher#apply]].
 * It can be attached to a [[org.reactivestreams.Subscriber]] or be used as an input source for a
 * [[akka.stream.Flow]]. You can only attach one subscriber to this publisher.
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
 * If the actor is stopped the stream will be completed, unless it was not already terminated with
 * failure, completed or canceled.
 */
trait ActorPublisher[T] extends Actor {
  import ActorPublisher._
  import ActorPublisher.Internal._

  private val state = ActorPublisherState(context.system)
  private var subscriber: Subscriber[Any] = _
  private var demand = 0L
  private var lifecycleState: LifecycleState = PreSubscriber

  /**
   * The state when the publisher is active, i.e. before the subscriber is attached
   * and when an subscriber is attached. It is allowed to
   * call [[#onComplete]] and [[#onError]] in this state. It is
   * allowed to call [[#onNext]] in this state when [[#totalDemand]]
   * is greater than zero.
   */
  final def isActive = lifecycleState == Active || lifecycleState == PreSubscriber

  /**
   * Total number of requested elements from the stream subscriber.
   * This actor automatically keeps tracks of this amount based on
   * incoming request messages and outgoing `onNext`.
   */
  final def totalDemand: Int = longToIntMax(demand)

  private def longToIntMax(n: Long): Int =
    if (n > Int.MaxValue) Int.MaxValue
    else n.toInt

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
    case Active | PreSubscriber ⇒
      if (demand > 0) {
        demand -= 1
        subscriber.onNext(element)
      } else
        throw new IllegalStateException(
          "onNext is not allowed when the stream has not requested elements, totalDemand was 0")
    case _: ErrorEmitted ⇒
      throw new IllegalStateException("onNext must not be called after onError")
    case Completed ⇒
      throw new IllegalStateException("onNext must not be called after onComplete")
    case Canceled ⇒ // drop
  }

  /**
   * Complete the stream. After that you are not allowed to
   * call [[#onNext]], [[#onError]] and [[#onComplete]].
   */
  def onComplete(): Unit = lifecycleState match {
    case Active | PreSubscriber ⇒
      lifecycleState = Completed
      if (subscriber ne null) // otherwise onComplete will be called when the subscription arrives
        subscriber.onComplete()
      subscriber = null // not used after onError
    case Completed ⇒
      throw new IllegalStateException("onComplete must only be called once")
    case _: ErrorEmitted ⇒
      throw new IllegalStateException("onComplete must not be called after onError")
    case Canceled ⇒ // drop
  }

  /**
   * Terminate the stream with failure. After that you are not allowed to
   * call [[#onNext]], [[#onError]] and [[#onComplete]].
   */
  def onError(cause: Throwable): Unit = lifecycleState match {
    case Active | PreSubscriber ⇒
      lifecycleState = ErrorEmitted(cause)
      if (subscriber ne null) // otherwise onError will be called when the subscription arrives
        subscriber.onError(cause)
      subscriber = null // not used after onError
    case _: ErrorEmitted ⇒
      throw new IllegalStateException("onError must only be called once")
    case Completed ⇒
      throw new IllegalStateException("onError must not be called after onComplete")
    case Canceled ⇒ // drop
  }

  protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case Request(elements) ⇒
      demand += elements
      super.aroundReceive(receive, msg)

    case Subscribe(sub) ⇒
      lifecycleState match {
        case PreSubscriber ⇒
          subscriber = sub
          lifecycleState = Active
          sub.onSubscribe(new ActorPublisherSubscription(self))
        case ErrorEmitted(cause) ⇒ sub.onError(cause)
        case Completed           ⇒ sub.onComplete()
        case Active | Canceled ⇒
          sub.onError(new IllegalStateException(s"ActorPublisher [$self] can only have one subscriber"))
      }

    case Cancel ⇒
      lifecycleState = Canceled
      demand = 0
      super.aroundReceive(receive, msg)

    case _ ⇒
      super.aroundReceive(receive, msg)
  }

  protected[akka] override def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // some state must survive restart
    state.set(self, ActorPublisherState.State(Option(subscriber), demand, lifecycleState))
    super.aroundPreRestart(reason, message)
  }

  protected[akka] override def aroundPostRestart(reason: Throwable): Unit = {
    state.get(self) foreach { s ⇒
      // restore previous state 
      subscriber = s.subscriber.orNull
      demand = s.demand
      lifecycleState = s.lifecycleState
    }
    state.remove(self)
    super.aroundPostRestart(reason)
  }

  protected[akka] override def aroundPostStop(): Unit = {
    state.remove(self)
    if (lifecycleState == Active) subscriber.onComplete()
    super.aroundPostStop()
  }

}

/**
 * INTERNAL API
 */
private[akka] case class ActorPublisherImpl[T](ref: ActorRef) extends Publisher[T] {
  import ActorPublisher._
  import ActorPublisher.Internal._

  override def subscribe(sub: Subscriber[T]): Unit =
    ref ! Subscribe(sub.asInstanceOf[Subscriber[Any]])
}

/**
 * INTERNAL API
 */
private[akka] class ActorPublisherSubscription[T](ref: ActorRef) extends Subscription {
  import ActorPublisher._
  override def request(elements: Int): Unit =
    if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
    else ref ! Request(elements)
  override def cancel(): Unit = ref ! Cancel
}

/**
 * INTERNAL API
 * Some state must survive restarts.
 */
private[akka] object ActorPublisherState extends ExtensionId[ActorPublisherState] with ExtensionIdProvider {
  import ActorPublisher.Internal.LifecycleState

  override def get(system: ActorSystem): ActorPublisherState = super.get(system)

  override def lookup = ActorPublisherState

  override def createExtension(system: ExtendedActorSystem): ActorPublisherState =
    new ActorPublisherState

  case class State(subscriber: Option[Subscriber[Any]], demand: Long, lifecycleState: LifecycleState)

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
