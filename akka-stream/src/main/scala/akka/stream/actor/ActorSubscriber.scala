/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.actor

import java.util.concurrent.ConcurrentHashMap
import org.reactivestreams.{ Subscriber, Subscription }
import akka.actor._
import akka.stream.impl.ReactiveStreamsCompliance

object ActorSubscriber {

  /**
   * Attach a [[ActorSubscriber]] actor as a [[org.reactivestreams.Subscriber]]
   * to a [[org.reactivestreams.Publisher]] or [[akka.stream.scaladsl.Flow]].
   */
  def apply[T](ref: ActorRef): Subscriber[T] = new ActorSubscriberImpl(ref)

  /**
   * INTERNAL API
   */
  private[akka] final case class OnSubscribe(subscription: Subscription)
      extends DeadLetterSuppression
      with NoSerializationVerificationNeeded

}

sealed abstract class ActorSubscriberMessage extends DeadLetterSuppression with NoSerializationVerificationNeeded

object ActorSubscriberMessage {
  final case class OnNext(element: Any) extends ActorSubscriberMessage
  final case class OnError(cause: Throwable) extends ActorSubscriberMessage
  case object OnComplete extends ActorSubscriberMessage

  /**
   * Java API: get the singleton instance of the `OnComplete` message
   */
  def onCompleteInstance = OnComplete
}

/**
 * An [[ActorSubscriber]] defines a `RequestStrategy` to control the stream back pressure.
 */
trait RequestStrategy {

  /**
   * Invoked by the [[ActorSubscriber]] after each incoming message to
   * determine how many more elements to request from the stream.
   *
   * @param remainingRequested current remaining number of elements that
   *   have been requested from upstream but not received yet
   * @return demand of more elements from the stream, returning 0 means that no
   *   more elements will be requested for now
   */
  def requestDemand(remainingRequested: Int): Int
}

/**
 * Requests one more element when `remainingRequested` is 0, i.e.
 * max one element in flight.
 */
case object OneByOneRequestStrategy extends RequestStrategy {
  def requestDemand(remainingRequested: Int): Int =
    if (remainingRequested == 0) 1 else 0

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * When request is only controlled with manual calls to
 * [[ActorSubscriber#request]].
 */
case object ZeroRequestStrategy extends RequestStrategy {
  def requestDemand(remainingRequested: Int): Int = 0

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

object WatermarkRequestStrategy {

  /**
   * Create [[WatermarkRequestStrategy]] with `lowWatermark` as half of
   * the specified `highWatermark`.
   */
  def apply(highWatermark: Int): WatermarkRequestStrategy = new WatermarkRequestStrategy(highWatermark)
}

/**
 * Requests up to the `highWatermark` when the `remainingRequested` is
 * below the `lowWatermark`. This a good strategy when the actor performs work itself.
 */
final case class WatermarkRequestStrategy(highWatermark: Int, lowWatermark: Int) extends RequestStrategy {
  require(lowWatermark >= 0, "lowWatermark must be >= 0")
  require(highWatermark >= lowWatermark, "highWatermark must be >= lowWatermark")

  /**
   * Create [[WatermarkRequestStrategy]] with `lowWatermark` as half of
   * the specified `highWatermark`.
   */
  def this(highWatermark: Int) = this(highWatermark, lowWatermark = math.max(1, highWatermark / 2))

  def requestDemand(remainingRequested: Int): Int =
    if (remainingRequested < lowWatermark)
      highWatermark - remainingRequested
    else 0
}

/**
 * Requests up to the `max` and also takes the number of messages
 * that have been queued internally or delegated to other actors into account.
 * Concrete subclass must implement [[#inFlightInternally]].
 * It will request elements in minimum batches of the defined [[#batchSize]].
 */
abstract class MaxInFlightRequestStrategy(max: Int) extends RequestStrategy {

  /**
   * Concrete subclass must implement this method to define how many
   * messages that are currently in progress or queued.
   */
  def inFlightInternally: Int

  /**
   * Elements will be requested in minimum batches of this size.
   * Default is 5. Subclass may override to define the batch size.
   */
  def batchSize: Int = 5

  override def requestDemand(remainingRequested: Int): Int = {
    val batch = math.min(batchSize, max)
    if ((remainingRequested + inFlightInternally) <= (max - batch))
      math.max(0, max - remainingRequested - inFlightInternally)
    else 0
  }
}

/**
 * Extend/mixin this trait in your [[akka.actor.Actor]] to make it a
 * stream subscriber with full control of stream back pressure. It will receive
 * [[ActorSubscriberMessage.OnNext]], [[ActorSubscriberMessage.OnComplete]] and [[ActorSubscriberMessage.OnError]]
 * messages from the stream. It can also receive other, non-stream messages, in
 * the same way as any actor.
 *
 * Attach the actor as a [[org.reactivestreams.Subscriber]] to the stream with
 * Scala API [[ActorSubscriber#apply]], or Java API [[UntypedActorSubscriber#create]] or
 * Java API compatible with lambda expressions [[AbstractActorSubscriber#create]].
 *
 * Subclass must define the [[RequestStrategy]] to control stream back pressure.
 * After each incoming message the `ActorSubscriber` will automatically invoke
 * the [[RequestStrategy#requestDemand]] and propagate the returned demand to the stream.
 * The provided [[WatermarkRequestStrategy]] is a good strategy if the actor
 * performs work itself.
 * The provided [[MaxInFlightRequestStrategy]] is useful if messages are
 * queued internally or delegated to other actors.
 * You can also implement a custom [[RequestStrategy]] or call [[#request]] manually
 * together with [[ZeroRequestStrategy]] or some other strategy. In that case
 * you must also call [[#request]] when the actor is started or when it is ready, otherwise
 * it will not receive any elements.
 *
 * @deprecated Use `akka.stream.stage.GraphStage` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.
 */
@deprecated(
  "Use `akka.stream.stage.GraphStage` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.",
  since = "2.5.0")
trait ActorSubscriber extends Actor {
  import ActorSubscriber._
  import ActorSubscriberMessage._

  private[this] val state = ActorSubscriberState(context.system)
  private[this] var subscription: Option[Subscription] = None
  private[this] var requested: Long = 0
  private[this] var _canceled = false

  protected def requestStrategy: RequestStrategy

  final def canceled: Boolean = _canceled

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case _: OnNext =>
      requested -= 1
      if (!_canceled) {
        super.aroundReceive(receive, msg)
        request(requestStrategy.requestDemand(remainingRequested))
      }
    case OnSubscribe(sub) =>
      if (subscription.isEmpty) {
        subscription = Some(sub)
        if (_canceled) {
          context.stop(self)
          sub.cancel()
        } else if (requested != 0)
          sub.request(remainingRequested)
      } else
        sub.cancel()
    case OnComplete | OnError(_) =>
      if (!_canceled) {
        _canceled = true
        super.aroundReceive(receive, msg)
      }
    case _ =>
      super.aroundReceive(receive, msg)
      request(requestStrategy.requestDemand(remainingRequested))
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPreStart(): Unit = {
    super.aroundPreStart()
    request(requestStrategy.requestDemand(remainingRequested))
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPostRestart(reason: Throwable): Unit = {
    state.get(self).foreach { s =>
      // restore previous state
      subscription = s.subscription
      requested = s.requested
      _canceled = s.canceled
    }
    state.remove(self)
    super.aroundPostRestart(reason)
    request(requestStrategy.requestDemand(remainingRequested))
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // some state must survive restart
    state.set(self, ActorSubscriberState.State(subscription, requested, _canceled))
    super.aroundPreRestart(reason, message)
  }

  /**
   * INTERNAL API
   */
  protected[akka] override def aroundPostStop(): Unit = {
    state.remove(self)
    if (!_canceled) subscription.foreach(_.cancel())
    super.aroundPostStop()
  }

  /**
   * Request a number of elements from upstream.
   */
  protected def request(elements: Long): Unit =
    if (elements > 0 && !_canceled) {
      // if we don't have a subscription yet, it will be requested when it arrives
      subscription.foreach(_.request(elements))
      requested += elements
    }

  /**
   * Cancel upstream subscription.
   * No more elements will be delivered after cancel.
   *
   * The [[ActorSubscriber]] will be stopped immediately after signaling cancellation.
   * In case the upstream subscription has not yet arrived the Actor will stay alive
   * until a subscription arrives, cancel it and then stop itself.
   */
  protected def cancel(): Unit =
    if (!_canceled) {
      subscription match {
        case Some(s) =>
          context.stop(self)
          s.cancel()
        case _ =>
          _canceled = true // cancel will be signaled once a subscription arrives
      }
    }

  /**
   * The number of stream elements that have already been requested from upstream
   * but not yet received.
   */
  protected def remainingRequested: Int = longToIntMax(requested)

  private def longToIntMax(n: Long): Int =
    if (n > Int.MaxValue) Int.MaxValue
    else n.toInt
}

/**
 * INTERNAL API
 */
private[akka] final class ActorSubscriberImpl[T](val impl: ActorRef) extends Subscriber[T] {
  import ActorSubscriberMessage._
  override def onError(cause: Throwable): Unit = {
    ReactiveStreamsCompliance.requireNonNullException(cause)
    impl ! OnError(cause)
  }
  override def onComplete(): Unit = impl ! OnComplete
  override def onNext(element: T): Unit = {
    ReactiveStreamsCompliance.requireNonNullElement(element)
    impl ! OnNext(element)
  }
  override def onSubscribe(subscription: Subscription): Unit = {
    ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
    impl ! ActorSubscriber.OnSubscribe(subscription)
  }
}

/**
 * INTERNAL API
 * Some state must survive restarts.
 */
private[akka] object ActorSubscriberState extends ExtensionId[ActorSubscriberState] with ExtensionIdProvider {
  override def get(system: ActorSystem): ActorSubscriberState = super.get(system)

  override def lookup() = ActorSubscriberState

  override def createExtension(system: ExtendedActorSystem): ActorSubscriberState =
    new ActorSubscriberState

  final case class State(subscription: Option[Subscription], requested: Long, canceled: Boolean)

}

/**
 * INTERNAL API
 */
private[akka] class ActorSubscriberState extends Extension {
  import ActorSubscriberState.State
  private val state = new ConcurrentHashMap[ActorRef, State]

  def get(ref: ActorRef): Option[State] = Option(state.get(ref))

  def set(ref: ActorRef, s: State): Unit = state.put(ref, s)

  def remove(ref: ActorRef): Unit = state.remove(ref)
}
