package akka.streams

import akka.streams.testkit.TestKit
import rx.async.api
import scala.concurrent.duration._
import akka.testkit.duration2TestDuration
import Operation._

trait ImplementationFactoryOperationSpec extends ImplementationFactorySpec {
  object TestException extends RuntimeException

  "A processor built from an ImplementationFactory" - {
    "if uninitialized without publisher" - {
      "buffer upstream requests when subscriber requests elements" in {
        val upstream = TestKit.producerProbe[Int]()
        val downstream = TestKit.consumerProbe[Int]()

        val processed = Identity[Int]().toProcessor()
        processed.link(downstream)
        val downstreamSubscription = downstream.expectSubscription()
        downstreamSubscription.requestMore(1)

        upstream.link(processed)

        val upstreamSubscription = upstream.expectSubscription()
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext(42)

        downstream.expectNext(42)
      }
      "subscriber cancels subscription before upstream is attached" in {
        val downstream = TestKit.consumerProbe[Int]()

        val processed = Identity[Int]().toProcessor()
        processed.link(downstream)
        val downstreamSubscription = downstream.expectSubscription()
        downstreamSubscription.requestMore(1)
        downstreamSubscription.cancel()

        val upstream = TestKit.producerProbe[Int]()
        upstream.link(processed)

        val upstreamSubscription = upstream.expectSubscription()
        upstreamSubscription.expectCancellation()
      }
    }
    "work uninitialized without subscriber" - {
      "upstream completes" in {
        val processed = Identity[Int]().toProcessor()

        val upstream = TestKit.producerProbe[Int]()
        upstream.link(processed)
        val upstreamSubscription = upstream.expectSubscription()
        upstreamSubscription.sendComplete()

        // we need some leeway for the actor to do its processing before
        // the fanOut will have registered that the downstream is complete
        Thread.sleep(100)

        val downstream = TestKit.consumerProbe[Int]()
        processed.link(downstream)
        // without the sleep you will get an onSubscribe first here
        // downstream.expectSubscription
        downstream.expectComplete()
      }
      "upstream errs out" in {
        val processed = Identity[Int]().toProcessor()

        val upstream = TestKit.producerProbe[Int]()
        upstream.link(processed)
        val upstreamSubscription = upstream.expectSubscription()
        upstreamSubscription.sendError(TestException)

        // see "upstream completes"
        Thread.sleep(100)

        val downstream = TestKit.consumerProbe[Int]()
        processed.link(downstream)
        downstream.expectError(TestException)
      }
    }
    "work initialized" - {
      "subscriber requests elements" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
      }
      "publisher sends element" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("test")
        downstream.expectNext("test")
      }
      "publisher sends elements and then completes" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.sendComplete()
        downstream.expectNext("test")
        downstream.expectComplete()
      }
      "publisher immediately completes" in new InitializedChainSetup(Identity[String]()) {
        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
      "publisher immediately fails" in new InitializedChainSetup(Identity[String]()) {
        object WeirdError extends RuntimeException("weird test exception")
        upstreamSubscription.sendError(WeirdError)
        downstream.expectError(WeirdError)
      }
      "operation publishes Producer" in new InitializedChainSetup[String, api.Producer[String]](Span[String](_ == "end").expose) {
        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1)

        upstreamSubscription.sendNext("a")
        val subStream = downstream.expectNext()
        val subStreamConsumer = TestKit.consumerProbe[String]()
        subStream.getPublisher.subscribe(subStreamConsumer.getSubscriber)
        val subStreamSubscription = subStreamConsumer.expectSubscription()

        subStreamSubscription.requestMore(1)
        subStreamConsumer.expectNext("a")

        subStreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("end")
        subStreamConsumer.expectNext("end")
        subStreamConsumer.expectComplete()

        upstreamSubscription.sendNext("test")
        val subStream2 = downstream.expectNext()
        val subStreamConsumer2 = TestKit.consumerProbe[String]()
        subStream2.getPublisher.subscribe(subStreamConsumer2.getSubscriber)
        val subStreamSubscription2 = subStreamConsumer2.expectSubscription()

        subStreamSubscription2.requestMore(1)
        subStreamConsumer2.expectNext("test")

        subStreamSubscription2.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("abc")
        subStreamConsumer2.expectNext("abc")

        subStreamSubscription2.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("end")
        upstreamSubscription.sendComplete()
        downstream.expectComplete()
        subStreamConsumer2.expectNext("end")
        subStreamConsumer2.expectComplete()
      }
      "operation consumes Producer" in new InitializedChainSetup[Source[String], String](Flatten())(factoryWithFanOutBuffer(16)) {
        downstreamSubscription.requestMore(4)
        upstream.expectRequestMore(upstreamSubscription, 1)

        val subStream = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream)
        val subStreamSubscription = subStream.expectSubscription()
        subStream.expectRequestMore(subStreamSubscription, 4)
        subStreamSubscription.sendNext("test")
        downstream.expectNext("test")
        subStreamSubscription.sendNext("abc")
        downstream.expectNext("abc")
        subStreamSubscription.sendComplete()

        upstream.expectRequestMore(upstreamSubscription, 1)

        val subStream2 = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream2)
        upstreamSubscription.sendComplete()
        val subStreamSubscription2 = subStream2.expectSubscription()
        subStream2.expectRequestMore(subStreamSubscription2, 2)
        subStreamSubscription2.sendNext("123")
        downstream.expectNext("123")

        subStreamSubscription2.sendComplete()
        downstream.expectComplete()
      }
      "combined operation spanning internal subscription" in new InitializedChainSetup[Int, Int](Span[Int](_ % 3 == 0).flatten) {
        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)

        upstreamSubscription.sendNext(1)
        downstream.expectNext(1)

        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext(2)
        downstream.expectNext(2)

        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext(3)
        downstream.expectNext(3)

        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext(4)
        downstream.expectNext(4)

        downstreamSubscription.requestMore(1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext(5)
        upstreamSubscription.sendComplete()
        downstream.expectNext(5)
        downstream.expectComplete()
      }
    }
    "work with multiple subscribers (FanOutBox)" - {
      "adapt speed to the currently slowest consumer" in new InitializedChainSetupWithFanOutBuffer(Identity[Symbol](), 1) {
        val downstream2 = TestKit.consumerProbe[Symbol]()
        processed.link(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1) // because fanOutBox has buffer of size 1

        upstreamSubscription.sendNext('firstElement)
        downstream.expectNext('firstElement)

        downstream2Subscription.requestMore(1)
        downstream2.expectNext('firstElement)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext('element2)

        downstream.expectNext('element2)

        downstream2Subscription.requestMore(1)
        downstream2.expectNext('element2)
      }
      "incoming subscriber while elements were requested before" in new InitializedChainSetupWithFanOutBuffer(Identity[Symbol](), 1) {
        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext('a1)
        downstream.expectNext('a1)

        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext('a2)
        downstream.expectNext('a2)

        upstream.expectRequestMore(upstreamSubscription, 1)

        // link now while an upstream element is already requested
        val downstream2 = TestKit.consumerProbe[Symbol]()
        processed.link(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        // situation here:
        // downstream 1 now has 3 outstanding
        // downstream 2 has 0 outstanding

        upstreamSubscription.sendNext('a3)
        downstream.expectNext('a3)
        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.requestMore(1)
        downstream2.expectNext('a3)

        // d1 now has 2 outstanding
        // d2 now has 0 outstanding
        // buffer should be empty so we should be requesting one new element

        upstream.expectRequestMore(upstreamSubscription, 1) // because of buffer size 1
      }
      "blocking subscriber cancels subscription" in new InitializedChainSetupWithFanOutBuffer(Identity[Symbol](), 1) {
        val downstream2 = TestKit.consumerProbe[Symbol]()
        processed.link(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(5)
        upstreamSubscription.expectRequestMore(1) // because fanOutBox has buffer of size 1

        upstreamSubscription.sendNext('firstElement)
        downstream.expectNext('firstElement)

        downstream2Subscription.requestMore(1)
        downstream2.expectNext('firstElement)
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext('element2)

        downstream.expectNext('element2)
        upstream.expectNoMsg(100.millis.dilated)
        // should unblock fanoutbox
        downstream2Subscription.cancel()
        upstreamSubscription.expectRequestMore(1)
      }
      "children Producers must support multiple subscribers" in pending
    }
    "work in special situations" - {
      "single subscriber cancels subscription while receiving data" in new InitializedChainSetupWithFanOutBuffer(Identity[String](), 1) {
        downstreamSubscription.requestMore(5)
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("test2")
        upstreamSubscription.expectRequestMore(1)
        downstream.expectNext("test")
        downstream.expectNext("test2")
        downstreamSubscription.cancel()

        // because of the "must cancel its upstream Subscription if its last downstream Subscription has been cancelled" rule
        upstreamSubscription.expectCancellation()
      }
    }
    "after initial upstream was completed" - {
      "future subscribers' onComplete should be called instead of onSubscribed" in new InitializedChainSetupWithFanOutBuffer(Identity[Symbol](), 1) {
        val downstream2 = TestKit.consumerProbe[Symbol]()
        // don't link it just yet

        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext('a1)
        downstream.expectNext('a1)

        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext('a2)
        downstream.expectNext('a2)

        upstream.expectRequestMore(upstreamSubscription, 1)

        // link now while an upstream element is already requested
        processed.link(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        upstreamSubscription.sendNext('a3)
        upstreamSubscription.sendComplete()
        downstream.expectNext('a3)
        downstream.expectComplete()

        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.requestMore(1)
        downstream2.expectNext('a3)
        downstream2.expectComplete()

        upstream.expectNoMsg(100.millis.dilated)

        val downstream3 = TestKit.consumerProbe[Symbol]()
        processed.link(downstream3)
        downstream3.expectComplete()
      }
    }
    "after initial upstream reported an error" - {
      "future subscribers' onError should be called instead of onSubscribed" in new InitializedChainSetupWithFanOutBuffer(Map[Int, Int](_ ⇒ throw TestException), 1) {
        downstreamSubscription.requestMore(1)
        upstreamSubscription.expectRequestMore(1)

        upstreamSubscription.sendNext(5)
        upstreamSubscription.expectCancellation()
        downstream.expectError(TestException)

        val downstream2 = TestKit.consumerProbe[Int]()
        processed.link(downstream2)
        downstream2.expectError(TestException)
      }
    }
    "when all subscriptions were cancelled" - {
      "future subscribers' onError should be called" in new InitializedChainSetupWithFanOutBuffer(Identity[Symbol](), 1) {
        downstreamSubscription.cancel()
        upstreamSubscription.expectCancellation()

        val downstream2 = TestKit.consumerProbe[Symbol]()
        processed.link(downstream2)
        downstream2.expectError()
      }
    }
    "if an internal error occurs" - {
      "upstream should be cancelled" in pending
      "subscribers' onError method should be called" in pending
      "future subscribers' onError should be called instead of onSubscribed" in pending
    }
  }

  class InitializedChainSetupWithFanOutBuffer[I, O](operation: Operation[I, O], capacity: Int) extends InitializedChainSetup(operation)(factoryWithFanOutBuffer(capacity))
  class InitializedChainSetup[I, O](operation: Operation[I, O])(implicit factory: ImplementationFactory) {
    val upstream = TestKit.producerProbe[I]()
    val downstream = TestKit.consumerProbe[O]()

    val processor = operation.toProcessor()
    upstream.link(processor)
    val processed = processor
    val upstreamSubscription = upstream.expectSubscription()
    processed.link(downstream)
    val downstreamSubscription = downstream.expectSubscription()
  }
}
