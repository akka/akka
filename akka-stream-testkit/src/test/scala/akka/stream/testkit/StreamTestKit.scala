/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.actor.ActorSystem
import akka.stream.impl.{ EmptyPublisher, ErrorPublisher }
import akka.testkit.TestProbe
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

import scala.concurrent.duration.FiniteDuration

object StreamTestKit {

  /**
   * Subscribes the subscriber and completes after the first request.
   */
  def lazyEmptyPublisher[T]: Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
      subscriber.onSubscribe(CompletedSubscription(subscriber))
  }

  /**
   * Signals error to subscribers immediately, before handing out subscription.
   */
  def errorPublisher[T](cause: Throwable): Publisher[T] = ErrorPublisher(cause: Throwable).asInstanceOf[Publisher[T]]

  def emptyPublisher[T](): Publisher[T] = EmptyPublisher[T]

  /**
   * Subscribes the subscriber and signals error after the first request.
   */
  def lazyErrorPublisher[T](cause: Throwable): Publisher[T] = new Publisher[T] {
    override def subscribe(subscriber: Subscriber[_ >: T]): Unit =
      subscriber.onSubscribe(FailedSubscription(subscriber, cause))
  }

  private case class FailedSubscription[T](subscriber: Subscriber[T], cause: Throwable) extends Subscription {
    override def request(elements: Long): Unit = subscriber.onError(cause)
    override def cancel(): Unit = ()
  }

  private case class CompletedSubscription[T](subscriber: Subscriber[T]) extends Subscription {
    override def request(elements: Long): Unit = subscriber.onComplete()
    override def cancel(): Unit = ()
  }

  class AutoPublisher[T](probe: PublisherProbe[T], initialPendingRequests: Long = 0) {
    val subscription = probe.expectSubscription()
    var pendingRequests = initialPendingRequests

    def sendNext(elem: T): Unit = {
      if (pendingRequests == 0) pendingRequests = subscription.expectRequest()
      pendingRequests -= 1
      subscription.sendNext(elem)
    }

    def sendComplete(): Unit = subscription.sendComplete()

    def sendError(cause: Exception): Unit = subscription.sendError(cause)
  }

  sealed trait SubscriberEvent
  case class OnSubscribe(subscription: Subscription) extends SubscriberEvent
  case class OnNext[I](element: I) extends SubscriberEvent
  case object OnComplete extends SubscriberEvent
  case class OnError(cause: Throwable) extends SubscriberEvent

  sealed trait PublisherEvent
  case class Subscribe(subscription: Subscription) extends PublisherEvent
  case class CancelSubscription(subscription: Subscription) extends PublisherEvent
  case class RequestMore(subscription: Subscription, elements: Long) extends PublisherEvent

  case class PublisherProbeSubscription[I](subscriber: Subscriber[_ >: I], publisherProbe: TestProbe) extends Subscription {
    def request(elements: Long): Unit = publisherProbe.ref ! RequestMore(this, elements)
    def cancel(): Unit = publisherProbe.ref ! CancelSubscription(this)

    def expectRequest(n: Long): Unit = publisherProbe.expectMsg(RequestMore(this, n))
    def expectRequest(): Long = publisherProbe.expectMsgPF() {
      case RequestMore(sub, n) if sub eq this ⇒ n
    }

    def expectCancellation(): Unit = publisherProbe.fishForMessage() {
      case CancelSubscription(sub) if sub eq this ⇒ true
      case RequestMore(sub, _) if sub eq this     ⇒ false
    }

    def sendNext(element: I): Unit = subscriber.onNext(element)
    def sendComplete(): Unit = subscriber.onComplete()
    def sendError(cause: Exception): Unit = subscriber.onError(cause)
  }

  case class SubscriberProbe[I]()(implicit system: ActorSystem) extends Subscriber[I] {
    val probe = TestProbe()

    def expectSubscription(): Subscription = probe.expectMsgType[OnSubscribe].subscription
    def expectEvent(event: SubscriberEvent): Unit = probe.expectMsg(event)
    def expectNext(element: I): Unit = probe.expectMsg(OnNext(element))
    def expectNext(e1: I, e2: I, es: I*): Unit = {
      val all = e1 +: e2 +: es
      all.foreach(e ⇒ probe.expectMsg(OnNext(e)))
    }

    def expectNext(): I = probe.expectMsgType[OnNext[I]].element
    def expectComplete(): Unit = probe.expectMsg(OnComplete)

    def expectError(cause: Throwable): Unit = probe.expectMsg(OnError(cause))
    def expectError(): Throwable = probe.expectMsgType[OnError].cause

    def expectErrorOrSubscriptionFollowedByError(cause: Throwable): Unit = {
      val t = expectErrorOrSubscriptionFollowedByError()
      assert(t == cause, s"expected $cause, found $cause")
    }

    def expectErrorOrSubscriptionFollowedByError(): Throwable =
      probe.expectMsgPF() {
        case s: OnSubscribe ⇒
          s.subscription.request(1)
          expectError()
        case OnError(cause) ⇒ cause
      }

    def expectCompletedOrSubscriptionFollowedByComplete(): Unit = {
      probe.expectMsgPF() {
        case s: OnSubscribe ⇒
          s.subscription.request(1)
          expectComplete()
        case OnComplete ⇒
      }
    }

    def expectNoMsg(): Unit = probe.expectNoMsg()
    def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)

    def onSubscribe(subscription: Subscription): Unit = probe.ref ! OnSubscribe(subscription)
    def onNext(element: I): Unit = probe.ref ! OnNext(element)
    def onComplete(): Unit = probe.ref ! OnComplete
    def onError(cause: Throwable): Unit = probe.ref ! OnError(cause)

    // Keeping equality
    // FIXME: This and PublisherProbe should not be a case class so that we don't need this equality reversal
    override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
    override def hashCode(): Int = System.identityHashCode(this)
  }

  case class PublisherProbe[I]()(implicit system: ActorSystem) extends Publisher[I] {
    val probe: TestProbe = TestProbe()

    def subscribe(subscriber: Subscriber[_ >: I]): Unit = {
      val subscription: PublisherProbeSubscription[I] = new PublisherProbeSubscription[I](subscriber, probe)
      probe.ref ! Subscribe(subscription)
      subscriber.onSubscribe(subscription)
    }

    def expectSubscription(): PublisherProbeSubscription[I] =
      probe.expectMsgType[Subscribe].subscription.asInstanceOf[PublisherProbeSubscription[I]]

    def expectRequest(subscription: Subscription, n: Int): Unit = probe.expectMsg(RequestMore(subscription, n))

    def expectNoMsg(): Unit = probe.expectNoMsg()
    def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)

    def getPublisher: Publisher[I] = this
  }
}
