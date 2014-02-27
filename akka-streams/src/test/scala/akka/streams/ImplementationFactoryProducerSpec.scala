package akka.streams

import scala.concurrent.duration._

import akka.streams.testkit.TestKit
import akka.testkit.duration2TestDuration

import Operation._

trait ImplementationFactoryProducerSpec extends ImplementationFactorySpec {
  "A producer built by an ImplementationFactory" - {
    "work in running state" - {
      "for SingletonSource" in new InitializedChainSetup(SingletonSource("test")) {
        downstreamSubscription.requestMore(2)
        downstream.expectNext("test")
        downstream.expectComplete()
      }
      "for FromIterableSource" - {
        "empty" in {
          val producer = FromIterableSource[String](Nil).toProducer()
          val downstream = TestKit.consumerProbe[String]()
          producer.link(downstream)
          downstream.expectComplete()
        }
        "with elements" in new InitializedChainSetup(FromIterableSource(Seq(1, 2, 3))) {
          downstreamSubscription.requestMore(2)
          downstream.expectNext(1)
          downstream.expectNext(2)
          downstream.expectNoMsg(100.millis.dilated)
          downstreamSubscription.requestMore(1)
          downstream.expectNext(3)
          downstream.expectComplete()
        }
      }
      "for FromProducerSource" - {
        "simple" in {
          val producerProbe = TestKit.producerProbe[String]()
          new InitializedChainSetup[String](FromProducerSource(producerProbe))(factoryWithFanOutBuffer(1)) {
            downstreamSubscription.requestMore(2)
            val upstreamSubscription = producerProbe.expectSubscription()
            // default fan-out-box with buffer size 1 translates any incoming request to one upstream
            upstreamSubscription.expectRequestMore(1)
            upstreamSubscription.sendNext("text")
            upstreamSubscription.expectRequestMore(1)
            downstream.expectNext("text")
            upstreamSubscription.sendNext("more text")
            upstreamSubscription.sendComplete()
            downstream.expectNext("more text")
            downstream.expectComplete()
          }
        }
        "single subscriber cancels subscription while receiving data" in {
          val producerProbe = TestKit.producerProbe[String]()
          new InitializedChainSetup[String](FromProducerSource(producerProbe))(factoryWithFanOutBuffer(1)) {
            downstreamSubscription.requestMore(2)
            val upstreamSubscription = producerProbe.expectSubscription()
            upstreamSubscription.expectRequestMore(1)
            upstreamSubscription.sendNext("text")
            upstreamSubscription.expectRequestMore(1)
            downstream.expectNext("text")
            downstreamSubscription.cancel()
            upstreamSubscription.expectCancellation()

            val downstream2 = TestKit.consumerProbe[String]()
            producer.link(downstream2)
            downstream2.expectError()
          }
        }
      }
      "for MappedSource" in new InitializedChainSetup(FromIterableSource(Seq(1, 2, 3)).map(_ + 1)) {
        downstreamSubscription.requestMore(2)
        downstream.expectNext(2)
        downstream.expectNext(3)
        downstream.expectNoMsg(100.millis.dilated)
        downstreamSubscription.requestMore(1)
        downstream.expectNext(4)
        downstream.expectComplete()
      }

      "work when attached to EmptyProducer" in {
        val producer = Producer.empty[Int].map(_ + 1).toProducer()

        val downstream = TestKit.consumerProbe[Int]()
        downstream.expectNoMsg(100.millis.dilated) // to make sure producer above has subscribed to source
        producer.link(downstream)

        downstream.expectComplete()
      }
    }
    "support multiple subscribers" - {
      "properly serve multiple subscribers to completion" in new InitializedChainSetup(FromIterableSource(Seq(1, 2, 3)).map(_ + 1)) {
        downstreamSubscription.requestMore(1)
        downstream.expectNext(2)
        downstream.expectNoMsg(100.millis.dilated)

        val downstream2 = TestKit.consumerProbe[Int]()
        producer.link(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(2)
        downstream.expectNext(3)
        downstream.expectNoMsg(100.millis.dilated)

        downstream2Subscription.requestMore(1)
        downstream2.expectNext(3)

        // buffer should allow one new element to be requested/delivered
        downstream.expectNext(4)
        downstream.expectComplete()

        downstream2Subscription.requestMore(1)
        downstream2.expectNext(4)
        downstream2.expectComplete()
      }
    }
    "have proper shutdown behavior" - {
      "after completion" in new InitializedChainSetup[String](SingletonSource("test")) {
        downstreamSubscription.requestMore(2)
        downstream.expectNext("test")
        downstream.expectComplete()
        downstreamSubscription.requestMore(1)

        val downstream2 = TestKit.consumerProbe[String]()
        producer.link(downstream2)
        downstream2.expectComplete()
      }
      object TestException extends RuntimeException
      "after error" in new InitializedChainSetup[String](SingletonSource("test").map(_ ⇒ throw TestException)) {
        downstreamSubscription.requestMore(2)
        downstream.expectError(TestException)
        downstreamSubscription.requestMore(1)

        val downstream2 = TestKit.consumerProbe[String]()
        producer.link(downstream2)
        downstream2.expectError(TestException)
      }
      "after all subscribers cancelled subscription" in new InitializedChainSetup[String](SingletonSource("test")) {
        downstreamSubscription.cancel()

        val downstream2 = TestKit.consumerProbe[String]()
        producer.link(downstream2)
        downstream2.expectError()
      }
    }
  }

  class InitializedChainSetup[O](source: Source[O])(implicit factory: ImplementationFactory) {
    val producer = source.toProducer()

    val downstream = TestKit.consumerProbe[O]()
    producer.link(downstream)
    val downstreamSubscription = downstream.expectSubscription()
  }
}
