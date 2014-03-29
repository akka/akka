/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.{ ChainSetup, StreamTestKit }
import akka.testkit._
import org.reactivestreams.api.Producer
import org.scalatest.FreeSpecLike

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class StreamSpec extends AkkaSpec {

  import system.dispatcher

  val genSettings = GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  val identity: Stream[Any] ⇒ Stream[Any] = in ⇒ in.map(e ⇒ e)
  val identity2: Stream[Any] ⇒ Stream[Any] = in ⇒ identity(in)

  "A Stream" must {
    for ((name, op) ← List("identity" -> identity, "identity2" -> identity2); n ← List(1, 2, 4)) {
      s"requests initial elements from upstream ($name, $n)" in {
        new ChainSetup(op, genSettings.copy(initialInputBufferSize = n)) {
          upstream.expectRequestMore(upstreamSubscription, settings.initialInputBufferSize)
        }
      }
    }

    "requests more elements from upstream when downstream requests more elements" in {
      new ChainSetup(identity, genSettings) {
        upstream.expectRequestMore(upstreamSubscription, settings.initialInputBufferSize)
        downstreamSubscription.requestMore(1)
        upstream.expectNoMsg(100.millis)
        downstreamSubscription.requestMore(2)
        upstream.expectNoMsg(100.millis)
        upstreamSubscription.sendNext("a")
        downstream.expectNext("a")
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstream.expectNoMsg(100.millis)
        upstreamSubscription.sendNext("b")
        upstreamSubscription.sendNext("c")
        upstreamSubscription.sendNext("d")
        downstream.expectNext("b")
        downstream.expectNext("c")
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstream.expectRequestMore(upstreamSubscription, 1)
      }
    }

    "deliver events when publisher sends elements and then completes" in {
      new ChainSetup(identity, genSettings) {
        downstreamSubscription.requestMore(1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.sendComplete()
        downstream.expectNext("test")
        downstream.expectComplete()
      }
    }

    "deliver complete signal when publisher immediately completes" in {
      new ChainSetup(identity, genSettings) {
        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
    }

    "deliver error signal when publisher immediately fails" in {
      new ChainSetup(identity, genSettings) {
        object WeirdError extends RuntimeException("weird test exception")
        EventFilter[WeirdError.type](occurrences = 1) intercept {
          upstreamSubscription.sendError(WeirdError)
          downstream.expectError(WeirdError)
        }
      }
    }

    "single subscriber cancels subscription while receiving data" in {
      new ChainSetup(identity, genSettings.copy(initialInputBufferSize = 1)) {
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

    //    FIXME the below commented out tests were implemented in ImplementationFactoryOperationSpec and might
    //    be intersting to port.
    //
    //    "operation publishes Producer" in new InitializedChainSetup[String, api.Producer[String]](Span[String](_ == "end").expose) {
    //        downstreamSubscription.requestMore(5)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //
    //        upstreamSubscription.sendNext("a")
    //        val subStream = downstream.expectNext()
    //        val subStreamConsumer = TestKit.consumerProbe[String]()
    //        subStream.getPublisher.subscribe(subStreamConsumer.getSubscriber)
    //        val subStreamSubscription = subStreamConsumer.expectSubscription()
    //
    //        subStreamSubscription.requestMore(1)
    //        subStreamConsumer.expectNext("a")
    //
    //        subStreamSubscription.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext("end")
    //        subStreamConsumer.expectNext("end")
    //        subStreamConsumer.expectComplete()
    //
    //        upstreamSubscription.sendNext("test")
    //        val subStream2 = downstream.expectNext()
    //        val subStreamConsumer2 = TestKit.consumerProbe[String]()
    //        subStream2.getPublisher.subscribe(subStreamConsumer2.getSubscriber)
    //        val subStreamSubscription2 = subStreamConsumer2.expectSubscription()
    //
    //        subStreamSubscription2.requestMore(1)
    //        subStreamConsumer2.expectNext("test")
    //
    //        subStreamSubscription2.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext("abc")
    //        subStreamConsumer2.expectNext("abc")
    //
    //        subStreamSubscription2.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext("end")
    //        upstreamSubscription.sendComplete()
    //        downstream.expectComplete()
    //        subStreamConsumer2.expectNext("end")
    //        subStreamConsumer2.expectComplete()
    //      }
    //      "operation consumes Producer" in new InitializedChainSetup[Source[String], String](Flatten())(factoryWithFanOutBuffer(16)) {
    //        downstreamSubscription.requestMore(4)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //
    //        val subStream = TestKit.producerProbe[String]()
    //        upstreamSubscription.sendNext(subStream)
    //        val subStreamSubscription = subStream.expectSubscription()
    //        subStream.expectRequestMore(subStreamSubscription, 4)
    //        subStreamSubscription.sendNext("test")
    //        downstream.expectNext("test")
    //        subStreamSubscription.sendNext("abc")
    //        downstream.expectNext("abc")
    //        subStreamSubscription.sendComplete()
    //
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //
    //        val subStream2 = TestKit.producerProbe[String]()
    //        upstreamSubscription.sendNext(subStream2)
    //        upstreamSubscription.sendComplete()
    //        val subStreamSubscription2 = subStream2.expectSubscription()
    //        subStream2.expectRequestMore(subStreamSubscription2, 2)
    //        subStreamSubscription2.sendNext("123")
    //        downstream.expectNext("123")
    //
    //        subStreamSubscription2.sendComplete()
    //        downstream.expectComplete()
    //      }
    //      "combined operation spanning internal subscription" in new InitializedChainSetup[Int, Int](Span[Int](_ % 3 == 0).flatten) {
    //        downstreamSubscription.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //
    //        upstreamSubscription.sendNext(1)
    //        downstream.expectNext(1)
    //
    //        downstreamSubscription.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext(2)
    //        downstream.expectNext(2)
    //
    //        downstreamSubscription.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext(3)
    //        downstream.expectNext(3)
    //
    //        downstreamSubscription.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext(4)
    //        downstream.expectNext(4)
    //
    //        downstreamSubscription.requestMore(1)
    //        upstream.expectRequestMore(upstreamSubscription, 1)
    //        upstreamSubscription.sendNext(5)
    //        upstreamSubscription.sendComplete()
    //        downstream.expectNext(5)
    //        downstream.expectComplete()
    //      }

  }

  "A Stream with multiple subscribers (FanOutBox)" must {
    "adapt speed to the currently slowest consumer" in {
      new ChainSetup(identity, genSettings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1) // because initialInputBufferSize=1

        upstreamSubscription.sendNext("firstElement")
        downstream.expectNext("firstElement")

        downstream2Subscription.requestMore(1)
        downstream2.expectNext("firstElement")
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("element2")

        downstream.expectNext("element2")

        downstream2Subscription.requestMore(1)
        downstream2.expectNext("element2")
      }
    }

    "incoming subscriber while elements were requested before" in {
      new ChainSetup(identity, genSettings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a1")
        downstream.expectNext("a1")

        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a2")
        downstream.expectNext("a2")

        upstream.expectRequestMore(upstreamSubscription, 1)

        // link now while an upstream element is already requested
        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        // situation here:
        // downstream 1 now has 3 outstanding
        // downstream 2 has 0 outstanding

        upstreamSubscription.sendNext("a3")
        downstream.expectNext("a3")
        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.requestMore(1)
        downstream2.expectNext("a3")

        // d1 now has 2 outstanding
        // d2 now has 0 outstanding
        // buffer should be empty so we should be requesting one new element

        upstream.expectRequestMore(upstreamSubscription, 1) // because of buffer size 1
      }
    }

    // FIXME failing test
    "blocking subscriber cancels subscription" ignore {
      new ChainSetup(identity, genSettings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(5)
        upstreamSubscription.expectRequestMore(1)

        upstreamSubscription.sendNext("firstElement")
        downstream.expectNext("firstElement")

        downstream2Subscription.requestMore(1)
        downstream2.expectNext("firstElement")
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("element2")
        upstreamSubscription.sendNext("element3")
        upstreamSubscription.sendNext("element4")

        upstreamSubscription.expectRequestMore(1)
        downstream2.expectNoMsg(100.millis.dilated)

        downstream.expectNext("element2")
        downstream.expectNoMsg(200.millis.dilated)
        upstream.expectNoMsg(100.millis.dilated)
        // should unblock fanoutbox
        downstream2Subscription.cancel()
        upstreamSubscription.expectRequestMore(1)
        downstream2.expectNoMsg(200.millis.dilated)
        // FIXME fails, "element3" disappears, "element4" is delivered here
        downstream.expectNext("element3")
        downstream.expectNext("element4")
      }
    }

    "after initial upstream was completed future subscribers' onComplete should be called instead of onSubscribed" in {
      new ChainSetup(identity, genSettings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
        val downstream2 = StreamTestKit.consumerProbe[Any]()
        // don't link it just yet

        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a1")
        downstream.expectNext("a1")

        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a2")
        downstream.expectNext("a2")

        upstream.expectRequestMore(upstreamSubscription, 1)

        // link now while an upstream element is already requested
        producer.produceTo(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        upstreamSubscription.sendNext("a3")
        upstreamSubscription.sendComplete()
        downstream.expectNext("a3")
        downstream.expectComplete()

        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.requestMore(1)
        downstream2.expectNext("a3")
        downstream2.expectComplete()

        // FIXME when adding a sleep before the following link this will fail with IllegalStateExc shut-down
        // what is the expected shutdown behavior? Is the title of this test wrong?
        //        val downstream3 = StreamTestKit.consumerProbe[Any]()
        //        producer.produceTo(downstream3)
        //        downstream3.expectComplete()
      }
    }

    "after initial upstream reported an error future subscribers' onError should be called instead of onSubscribed" in {
      new ChainSetup[Int, String](_.map(_ ⇒ throw TestException), genSettings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
        downstreamSubscription.requestMore(1)
        upstreamSubscription.expectRequestMore(1)

        EventFilter[TestException.type](occurrences = 1) intercept {
          upstreamSubscription.sendNext(5)
          upstreamSubscription.expectRequestMore(1)
          upstreamSubscription.expectCancellation()
          downstream.expectError(TestException)
        }

        val downstream2 = StreamTestKit.consumerProbe[String]()
        producer.produceTo(downstream2)
        // IllegalStateException shut down
        downstream2.expectError().getClass should be(classOf[IllegalStateException])
      }
    }

    "when all subscriptions were cancelled future subscribers' onError should be called" in {
      new ChainSetup(identity, genSettings.copy(initialInputBufferSize = 1)) {
        upstreamSubscription.expectRequestMore(1)
        downstreamSubscription.cancel()
        upstreamSubscription.expectCancellation()

        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        // IllegalStateException shut down
        downstream2.expectError().getClass should be(classOf[IllegalStateException])
      }
    }

    "if an internal error occurs upstream should be cancelled" in pending
    "if an internal error occurs subscribers' onError method should be called" in pending
    "if an internal error occurs future subscribers' onError should be called instead of onSubscribed" in pending

  }

  object TestException extends RuntimeException

}