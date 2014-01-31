package akka.streams.testkit

import akka.testkit.TestProbe
import rx.async.spi.{ Publisher, Subscriber, Subscription }
import rx.async.tck._
import akka.actor.ActorSystem
import scala.concurrent.duration.FiniteDuration

object TestKit {
  def consumerProbe[I]()(implicit system: ActorSystem): AkkaConsumerProbe[I] =
    new AkkaConsumerProbe[I] with Subscriber[I] { outer ⇒
      lazy val probe = TestProbe()

      def expectSubscription(): Subscription = probe.expectMsgType[OnSubscribe].subscription
      def expectEvent(event: ConsumerEvent): Unit = probe.expectMsg(event)
      def expectNext(element: I): Unit = probe.expectMsg(OnNext(element))
      def expectNext(): I = probe.expectMsgType[OnNext[I]].element
      def expectComplete(): Unit = probe.expectMsg(OnComplete)

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
          def expectCancellation(): Unit = probe.expectMsg(CancelSubscription(this))

          def sendNext(element: I): Unit = subscriber.onNext(element)
          def sendComplete(): Unit = subscriber.onComplete()
          def sendError(cause: Exception): Unit = subscriber.onError(cause)
        }
        subscriber.onSubscribe(subscription)
        probe.ref ! Subscribe(subscription)
      }

      def expectSubscription(): ActiveSubscription[I] =
        probe.expectMsgType[Subscribe].subscription.asInstanceOf[ActiveSubscription[I]]

      def expectRequestMore(subscription: Subscription, n: Int): Unit = probe.expectMsg(RequestMore(subscription, n))

      def expectNoMsg(): Unit = probe.expectNoMsg()
      def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)

      def getPublisher: Publisher[I] = this
    }
}
