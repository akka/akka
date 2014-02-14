package akka.streams

import org.scalatest.{ Tag, BeforeAndAfterAll, WordSpec, ShouldMatchers }
import rx.async.tck._
import akka.streams.testkit.TestKit
import akka.testkit.TestKitBase
import akka.actor.ActorSystem
import rx.async.api.Producer
import scala.concurrent.duration._
import akka.testkit.duration2TestDuration
import akka.streams.impl.{ RaceTrack }
import Operation._

abstract class ImplementationFactorySpec extends WordSpec with TestKitBase with ShouldMatchers with BeforeAndAfterAll {
  implicit def factory: ImplementationFactory

  "A processor built from an ImplementationFactory" should {
    "work uninitialized without publisher" when {
      "subscriber requests elements" in {
        val upstream = TestKit.producerProbe[Int]()
        val downstream = TestKit.consumerProbe[Int]()

        val processed = Identity[Int]().create()
        processed.link(downstream)
        val downstreamSubscription = downstream.expectSubscription()

        downstreamSubscription.requestMore(1)
        upstream.link(processed)
        val upstreamSubscription = upstream.expectSubscription()
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext(42)
        downstream.expectNext(42)
      }
      "subscriber cancels subscription and resubscribes" in pending
    }
    "work uninitialized without subscriber" when {
      "publisher completes" in pending
      "publisher errs out" in pending
    }
    "work initialized" when {
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
      "operation publishes Producer" in new InitializedChainSetup[String, Producer[String]](Span[String](_ == "end").expose) {
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
      "operation consumes Producer" in new InitializedChainSetup[Source[String], String](Flatten()) {
        downstreamSubscription.requestMore(4)
        upstream.expectRequestMore(upstreamSubscription, 1)

        val subStream = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream)
        val subStreamSubscription = subStream.expectSubscription()
        subStream.expectRequestMore(subStreamSubscription, 1)
        subStreamSubscription.sendNext("test")
        subStream.expectRequestMore(subStreamSubscription, 1)
        downstream.expectNext("test")
        subStreamSubscription.sendNext("abc")
        subStream.expectRequestMore(subStreamSubscription, 1)
        downstream.expectNext("abc")
        subStreamSubscription.sendComplete()

        upstream.expectRequestMore(upstreamSubscription, 1)

        val subStream2 = TestKit.producerProbe[String]()
        upstreamSubscription.sendNext(subStream2)
        upstreamSubscription.sendComplete()
        val subStreamSubscription2 = subStream2.expectSubscription()
        subStream2.expectRequestMore(subStreamSubscription2, 1)
        subStreamSubscription2.sendNext("123")
        subStream2.expectRequestMore(subStreamSubscription2, 1)
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
    "work with multiple subscribers (FanOutBox)" when {
      "adapt speed to the currently slowest consumer" in new InitializedChainSetup(Identity[Symbol]()) {
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
      "incoming subscriber while elements were requested before" in new InitializedChainSetup(Identity[Symbol]()) {
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
        downstream.expectNext('a3)
        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.requestMore(1)
        downstream2.expectNext('a3)

        upstream.expectRequestMore(upstreamSubscription, 1) // because of buffer size 1
      }
      "blocking subscriber cancels subscription" in new InitializedChainSetup(Identity[Symbol]()) {
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
        // ... but doesn't currently
        pending // TODO: fix RaceTrack
        upstreamSubscription.expectRequestMore(1)
      }
      "children Producers must support multiple subscribers" in pending
      "finish gracefully onComplete" in new InitializedChainSetup(Identity[Symbol]()) {
        val downstream2 = TestKit.consumerProbe[Symbol]()
        // don't link it just yet

        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1 /* or batch/window size? */ )
        upstreamSubscription.sendNext('a1)
        downstream.expectNext('a1)

        upstream.expectRequestMore(upstreamSubscription, 1 /* or batch/window size? */ )
        upstreamSubscription.sendNext('a2)
        downstream.expectNext('a2)

        upstream.expectRequestMore(upstreamSubscription, 1 /* or batch/window size? */ )

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
      }
      "finish gracefully if last subscriber needing data cancels subscription" in pending
      "finish gracefully onError" in new InitializedChainSetup(Identity[Symbol]()) {
        pending
      }
    }
    "work in special situations" when {
      "single subscriber cancels subscription while receiving data" in new InitializedChainSetup(Identity[String]()) {
        downstreamSubscription.requestMore(5)
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("test2")
        upstreamSubscription.expectRequestMore(1)
        downstream.expectNext("test")
        downstream.expectNext("test2")
        downstreamSubscription.cancel()
        // autoUnsubscribe?
        //upstreamSubscription.expectCancellation()
        pending
      }
    }
    "work after initial upstream was completed" when {}
  }

  override protected def afterAll(): Unit = system.shutdown()

  class InitializedChainSetup[I, O](operation: Operation[I, O])(implicit factory: ImplementationFactory) {
    val upstream = TestKit.producerProbe[I]()
    val downstream = TestKit.consumerProbe[O]()

    val processor = operation.create()
    upstream.link(processor)
    val processed = processor
    val upstreamSubscription = upstream.expectSubscription()
    processed.link(downstream)
    val downstreamSubscription = downstream.expectSubscription()
  }
}

