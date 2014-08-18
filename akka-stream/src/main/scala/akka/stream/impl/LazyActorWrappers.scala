package akka.stream.impl

import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean, AtomicReference }

import akka.actor.{ Props, ActorRef }
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 */
private[akka] object LazyActorPublisher {

  import akka.stream.actor.ActorSubscriber.SubscriberMessage

  class NormalShutdownException extends IllegalStateException("Cannot subscribe to shut-down spi.Publisher") with NoStackTrace
  val NormalShutdownReason: Option[Throwable] = Some(new NormalShutdownException)

  def apply[T](props: Props, name: String, equalityValue: Option[AnyRef] = None, materializer: ActorRef): LazyActorPublisher[T] =
    new LazyActorPublisher[T](props, name, equalityValue, materializer)

  def unapply(o: Any): Option[Any] = o match {
    case other: LazyActorPublisher[_] ⇒ other.equalityValue
    case _                            ⇒ None
  }

  sealed trait PublisherState[+T]

  case object Dormant extends PublisherState[Nothing]
  final case class Signaled(messages: List[SubscriberMessage], upstreamSubscription: Subscription) extends PublisherState[Nothing]
  final case class Subscribed[T](subscriptions: immutable.Map[LazySubscription[T], Long], messages: List[SubscriberMessage], first: Boolean, awakening: Boolean, upstreamSubscription: Subscription) extends PublisherState[T]
  final case class Awake[T](impl: ActorRef, pendingSubscribers: List[Subscriber[T]]) extends PublisherState[T]
  case object Terminated extends PublisherState[Nothing]

  case class OtherSubscribers[T](subscribers: List[Subscriber[T]])
}

/**
 * INTERNAL API
 */
private[akka] trait LazyElement {
  def name: String
  def props: Props
  def materializer: ActorRef
  def extraArguments: Any
}

/**
 * INTERNAL API
 */
private[akka] class LazySubscription[T]( final val publisher: LazyPublisherLike[T], final val subscriber: Subscriber[T]) extends SubscriptionWithCursor[T] {
  override def request(elements: Int): Unit =
    if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
    else publisher.request(this, elements)
  override def cancel(): Unit = publisher.cancel(this)
}

/**
 * INTERNAL API
 */
private[akka] trait LazyPublisherLike[T] extends Publisher[T] with LazyElement {
  import akka.stream.impl.LazyActorPublisher._
  import akka.stream.actor.ActorSubscriber.SubscriberMessage

  override def extraArguments: Any = null

  protected val state = new AtomicReference[PublisherState[T]](Dormant)
  @volatile private var shutdownReason: Option[Throwable] = None

  override def subscribe(s: Subscriber[T]): Unit = doSubscribe(s)
  def request(subscription: LazySubscription[T], elements: Int): Unit = doRequest(subscription, elements)
  def cancel(subscription: LazySubscription[T]): Unit = doCancel(subscription)

  protected def createSubscription(subscriber: Subscriber[T]): LazySubscription[T] = new LazySubscription[T](this, subscriber)

  @tailrec
  final protected def doRequest(subscription: LazySubscription[T], elements: Long): Unit = state.get match {
    case Awake(impl, _) ⇒
      impl ! RequestMore(subscription.asInstanceOf[LazySubscription[Any]], elements)
    case s @ Subscribed(subscriptions, _, first, _, _) ⇒
      val current: Long = subscriptions.getOrElse(subscription, 0)
      val total = current + elements
      val demand = if (elements == Long.MaxValue || (total < current)) Long.MaxValue else total
      if (state.compareAndSet(s, s.copy(
        subscriptions = subscriptions + (subscription -> demand),
        first = false))) {
        if (first) onFirstDownstreamRequest()
      } else doRequest(subscription, elements)
    case Dormant        ⇒ // WAT?
    case Signaled(_, _) ⇒ // WAT?
    case Terminated     ⇒ // Ignore
  }

  @tailrec
  final protected def doCancel(subscription: LazySubscription[T]): Unit = state.get match {
    case Awake(impl, _) ⇒ impl ! Cancel(subscription.asInstanceOf[LazySubscription[Any]])
    case s @ Subscribed(subscriptions, _, _, _, _) ⇒
      if (!state.compareAndSet(s, s.copy(subscriptions = subscriptions - subscription)))
        doCancel(subscription)
    case Dormant        ⇒ // WAT?
    case Signaled(_, _) ⇒ // WAT?
    case Terminated     ⇒ // Ignore
  }

  @tailrec
  final protected def doSubscribe(subscriber: Subscriber[T]): Unit = state.get match {
    case Dormant ⇒
      val subscription = createSubscription(subscriber)
      if (state.compareAndSet(Dormant, Subscribed(Map(subscription -> 0), Nil, first = true, awakening = false, null))) {
        subscriber.onSubscribe(subscription)
        extraArguments match {
          case OtherSubscribers(os) ⇒ os foreach (s ⇒ subscribe(s.asInstanceOf[Subscriber[T]]))
          case _                    ⇒
        }
      } else doSubscribe(subscriber)
    case a @ Awake(impl, pendingSubscribers) ⇒
      if (state.compareAndSet(a, Awake(impl, subscriber :: pendingSubscribers)))
        impl ! SubscribePending
      else
        doSubscribe(subscriber)
    case s @ Subscribed(subscriptions, _, _, _, _) ⇒
      // TODO: report duplicte subscriptions
      val subscription = createSubscription(subscriber)
      if (state.compareAndSet(s, s.copy(subscriptions = subscriptions + (subscription -> 0))))
        subscriber.onSubscribe(subscription)
      else
        doSubscribe(subscriber)
    case s @ Signaled(messages, upsub) ⇒
      val subscription = createSubscription(subscriber)
      if (state.compareAndSet(s, Subscribed(Map(subscription -> 0), messages, first = true, awakening = false, upsub))) {
        subscriber.onSubscribe(subscription)
        extraArguments match {
          case OtherSubscribers(os) ⇒ os foreach (s ⇒ subscribe(s.asInstanceOf[Subscriber[T]]))
          case _                    ⇒
        }
      } else doSubscribe(subscriber)
    case Terminated ⇒
      reportSubscribeError(subscriber)

  }

  protected def onFirstDownstreamRequest(): Unit

  protected def wakeUp(): Unit =
    materializer ! StreamSupervisor.Materialize(props, this)

  def takeEarlySubscribers(impl: ActorRef): immutable.Map[LazySubscription[T], Long] = {
    state.getAndSet(Awake(impl, Nil)) match {
      case Subscribed(subscriptions, messages, _, awakening, upsub) if awakening ⇒
        afterSetBackingActor(impl, messages, upsub)
        subscriptions
      case other ⇒
        throw new IllegalStateException(s"Unexpected state when taking early subscribers: $other")
    }
  }

  protected def afterSetBackingActor(impl: ActorRef, messages: List[SubscriberMessage], upstreamSubscription: Subscription): Unit

  private def reportSubscribeError(subscriber: Subscriber[T]): Unit =
    shutdownReason match {
      case Some(e) ⇒ subscriber.onError(e)
      case None    ⇒ subscriber.onComplete()
    }

  def takePendingSubscribers(): immutable.Seq[Subscriber[T]] = doTakePendingSubscribers()

  @tailrec private def doTakePendingSubscribers(): immutable.Seq[Subscriber[T]] = state.get match {
    case Dormant          ⇒ throw new IllegalStateException("Cannot take pending subscribers while dormant")
    case Terminated       ⇒ throw new IllegalStateException("Cannot take pending subscribers after terminated")
    case _: Signaled      ⇒ throw new IllegalStateException("Cannot take pending subscribers while being signaled")
    case _: Subscribed[_] ⇒ throw new IllegalStateException("Cannot take pending subscribers while waking up")
    case a @ Awake(impl, pendingSubscribers) ⇒
      if (state.compareAndSet(a, Awake(impl, Nil)))
        pendingSubscribers.reverse
      else
        doTakePendingSubscribers()
  }

  @tailrec
  final protected def doWakeUp(): Unit = state.get match {
    case s @ Subscribed(_, _, _, awakening, _) if !awakening ⇒
      if (state.compareAndSet(s, s.copy(awakening = true)))
        wakeUp()
      else
        doWakeUp()
    case _ ⇒ // Ignore
  }

  def shutdown(reason: Option[Throwable]): Unit = {
    state.getAndSet(Terminated) match {
      case Awake(_, pendingSubscribers) ⇒
        val refuseReason = reason.getOrElse(new IllegalStateException("Cannot subscribe after terminated"))
        pendingSubscribers.foreach(_.onError(refuseReason))
      case _ ⇒ // Nothing to do? // FIXME: waking up?
    }
  }

}

/**
 * INTERNAL API
 */
private[akka] class LazyActorPublisher[T](
  val props: Props,
  val name: String,
  val equalityValue: Option[Any],
  val materializer: ActorRef) extends LazyPublisherLike[T] {

  import akka.stream.actor.ActorSubscriber.SubscriberMessage
  import akka.stream.impl.LazyActorPublisher.Subscribed

  override protected def onFirstDownstreamRequest(): Unit = doWakeUp()

  override protected def afterSetBackingActor(impl: ActorRef, messages: List[SubscriberMessage], upstreamSubscription: Subscription): Unit = ()

  override def equals(o: Any): Boolean = (equalityValue, o) match {
    case (Some(v), LazyActorPublisher(otherValue)) ⇒ v.equals(otherValue)
    case _                                         ⇒ super.equals(o)
  }

  override def hashCode: Int = equalityValue match {
    case Some(v) ⇒ v.hashCode
    case None    ⇒ super.hashCode

  }
}

/**
 * INTERNAL API
 */
private[akka] class CloningLazyActorPublisher[T](
  _props: Props,
  _name: String,
  _equalityValue: Option[Any],
  _materializer: ActorRef) extends LazyActorPublisher[T](_props, _name, _equalityValue, _materializer) {

  private val cloneId = new AtomicLong(0)

  override def subscribe(s: Subscriber[T]): Unit = {
    val publisher = new LazyActorPublisher[T](props, s"$name-${cloneId.getAndIncrement}", equalityValue, materializer)
    publisher.subscribe(s)
  }

}

/**
 * INTERNAL API
 */
object LazyActorProcessor {
  def apply[A, B](props: Props, name: String, materializer: ActorRef, extraArguments: Any = null) =
    new LazyActorProcessor[A, B](props, name, materializer, extraArguments)
}

/**
 * INTERNAL API
 */
private[akka] class LazyActorProcessor[A, B](
  val props: Props,
  val name: String,
  val materializer: ActorRef,
  override val extraArguments: Any)
  extends Processor[A, B]
  with LazyPublisherLike[B] {

  import akka.stream.actor.ActorSubscriber.{ SubscriberMessage, OnError, OnNext, OnComplete, OnSubscribe }
  import akka.stream.impl.LazyActorPublisher._

  override def onSubscribe(subscription: Subscription): Unit = doOnSubscribe(subscription)

  @tailrec
  final private def doOnSubscribe(subscription: Subscription): Unit = state.get match {
    case Awake(impl, _) ⇒ impl ! OnSubscribe(subscription)
    case Dormant ⇒
      if (!state.compareAndSet(Dormant, Signaled(Nil, subscription)))
        doOnSubscribe(subscription)
    case s @ Signaled(messages, upsub) ⇒
      if (upsub ne null)
        subscription.cancel() // TODO is this the right logic?
      else if (!state.compareAndSet(s, s.copy(upstreamSubscription = subscription)))
        doOnSubscribe(subscription)
    case s @ Subscribed(subscriptions, messages, first, awakening, upsub) ⇒
      if (upsub ne null)
        subscription.cancel() // TODO is this the right logic?
      else if (!state.compareAndSet(s, s.copy(upstreamSubscription = subscription)))
        doOnSubscribe(subscription)
    case Terminated ⇒ // Ignore ???
  }

  override def onError(t: Throwable): Unit = doErrorOrCompleteOrNext(OnError(t), ignoreDormant = false)

  override def onComplete(): Unit = doErrorOrCompleteOrNext(OnComplete, ignoreDormant = false)

  override def onNext(t: A): Unit = doErrorOrCompleteOrNext(OnNext(t), ignoreDormant = true)

  @tailrec
  final private def doErrorOrCompleteOrNext(message: SubscriberMessage, ignoreDormant: Boolean): Unit = state.get match {
    case Awake(impl, _) ⇒ impl ! message
    case Dormant if !ignoreDormant ⇒
      if (!state.compareAndSet(Dormant, Signaled(List(message), null)))
        doErrorOrCompleteOrNext(message, ignoreDormant)
    case s @ Signaled(messages, _) ⇒
      if (!state.compareAndSet(s, s.copy(messages = message :: messages)))
        doErrorOrCompleteOrNext(message, ignoreDormant)
    case s @ Subscribed(_, messages, _, awakening, _) ⇒
      if (state.compareAndSet(s, s.copy(messages = message :: messages, awakening = true))) {
        if (!awakening) wakeUp()
      } else
        doErrorOrCompleteOrNext(message, ignoreDormant)
    case Terminated ⇒ // Ignore ???
  }

  override protected def afterSetBackingActor(impl: ActorRef, messages: List[SubscriberMessage], upstreamSubscription: Subscription): Unit = {
    if (upstreamSubscription ne null)
      impl ! OnSubscribe(upstreamSubscription)
    messages.reverse foreach {
      msg ⇒ impl ! msg
    }
  }

  override protected def onFirstDownstreamRequest(): Unit = doWakeUp()
}