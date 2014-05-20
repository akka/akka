/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.testkit

import akka.testkit.TestProbe
import org.reactivestreams.spi.{ Publisher, Subscriber, Subscription }
import org.reactivestreams.tck._
import akka.actor.ActorSystem
import scala.concurrent.duration.FiniteDuration
import scala.annotation.tailrec
import akka.stream.impl.EmptyProducer
import org.reactivestreams.api.Producer
import akka.stream.impl.ErrorProducer
import org.reactivestreams.api.Consumer

object StreamTestKit {
  def consumerProbe[I]()(implicit system: ActorSystem): AkkaConsumerProbe[I] =
    new AkkaConsumerProbe[I] with Subscriber[I] { outer ⇒
      lazy val probe = TestProbe()

      def expectSubscription(): Subscription = probe.expectMsgType[OnSubscribe].subscription
      def expectEvent(event: ConsumerEvent): Unit = probe.expectMsg(event)
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
            s.subscription.requestMore(1)
            expectError()
          case OnError(cause) ⇒ cause
        }

      def expectCompletedOrSubscriptionFollowedByComplete(): Unit = {
        probe.expectMsgPF() {
          case s: OnSubscribe ⇒
            s.subscription.requestMore(1)
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

      def getSubscriber: Subscriber[I] = this
    }

  def producerProbe[I]()(implicit system: ActorSystem): AkkaProducerProbe[I] =
    new AkkaProducerProbe[I] with Publisher[I] {
      lazy val probe: TestProbe = TestProbe()

      def subscribe(subscriber: Subscriber[I]): Unit = {
        lazy val subscription: ActiveSubscription[I] = new ActiveSubscription[I](subscriber) {
          def requestMore(elements: Int): Unit = probe.ref ! RequestMore(subscription, elements)
          def cancel(): Unit = probe.ref ! CancelSubscription(subscription)

          def expectRequestMore(n: Int): Unit = probe.expectMsg(RequestMore(subscription, n))
          def expectRequestMore(): Int = probe.expectMsgPF() {
            case RequestMore(`subscription`, n) ⇒ n
          }
          def expectCancellation(): Unit = probe.fishForMessage() {
            case CancelSubscription(`subscription`) ⇒ true
            case RequestMore(`subscription`, _)     ⇒ false
          }

          def sendNext(element: I): Unit = subscriber.onNext(element)
          def sendComplete(): Unit = subscriber.onComplete()
          def sendError(cause: Exception): Unit = subscriber.onError(cause)
        }
        probe.ref ! Subscribe(subscription)
        subscriber.onSubscribe(subscription)
      }

      def expectSubscription(): ActiveSubscription[I] =
        probe.expectMsgType[Subscribe].subscription.asInstanceOf[ActiveSubscription[I]]

      def expectRequestMore(subscription: Subscription, n: Int): Unit = probe.expectMsg(RequestMore(subscription, n))

      def expectNoMsg(): Unit = probe.expectNoMsg()
      def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)

      def getPublisher: Publisher[I] = this
    }

  /**
   * Completes subscribers immediately, before handing out subscription.
   */
  def emptyProducer[T]: Producer[T] = EmptyProducer.asInstanceOf[Producer[T]]

  /**
   * Subscribes the subscriber and completes after the first requestMore.
   */
  def lazyEmptyProducer[T]: Producer[T] = new Producer[T] with Publisher[T] {
    override def getPublisher = this
    override def produceTo(consumer: Consumer[T]): Unit = getPublisher.subscribe(consumer.getSubscriber)
    override def subscribe(subscriber: Subscriber[T]): Unit =
      subscriber.onSubscribe(CompletedSubscription(subscriber))
  }

  /**
   * Signals error to subscribers immediately, before handing out subscription.
   */
  def errorProducer[T](cause: Throwable): Producer[T] = ErrorProducer(cause: Throwable).asInstanceOf[Producer[T]]

  /**
   * Subscribes the subscriber and signals error after the first requestMore.
   */
  def lazyErrorProducer[T](cause: Throwable): Producer[T] = new Producer[T] with Publisher[T] {
    override def getPublisher = this
    override def produceTo(consumer: Consumer[T]): Unit = getPublisher.subscribe(consumer.getSubscriber)
    override def subscribe(subscriber: Subscriber[T]): Unit =
      subscriber.onSubscribe(FailedSubscription(subscriber, cause))
  }

  private case class FailedSubscription[T](subscriber: Subscriber[T], cause: Throwable) extends Subscription {
    override def requestMore(elements: Int): Unit = subscriber.onError(cause)
    override def cancel(): Unit = ()
  }

  private case class CompletedSubscription[T](subscriber: Subscriber[T]) extends Subscription {
    override def requestMore(elements: Int): Unit = subscriber.onComplete()
    override def cancel(): Unit = ()
  }

  class AutoProducer[T](probe: ProducerProbe[T], initialPendingRequests: Int = 0) {
    val subscription = probe.expectSubscription()
    var pendingRequests = initialPendingRequests

    def sendNext(elem: T): Unit = {
      if (pendingRequests == 0) pendingRequests = subscription.expectRequestMore()
      pendingRequests -= 1
      subscription.sendNext(elem)
    }
  }
}
