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
}
