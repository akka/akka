/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.{ AkkaSpec, ChainSetup, StreamTestKit }
import akka.testkit._
import org.reactivestreams.api.Producer
import org.scalatest.FreeSpecLike
import com.typesafe.config.ConfigFactory
import akka.stream.scaladsl.Flow

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {

  import system.dispatcher

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  val identity: Flow[Any] ⇒ Flow[Any] = in ⇒ in.map(e ⇒ e)
  val identity2: Flow[Any] ⇒ Flow[Any] = in ⇒ identity(in)

  "A Flow" must {

    for ((name, op) ← List("identity" -> identity, "identity2" -> identity2); n ← List(1, 2, 4)) {
      s"requests initial elements from upstream ($name, $n)" in {
        new ChainSetup(op, settings.copy(initialInputBufferSize = n)) {
          upstream.expectRequestMore(upstreamSubscription, settings.initialInputBufferSize)
        }
      }
    }

    "requests more elements from upstream when downstream requests more elements" in {
      new ChainSetup(identity, settings) {
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
      }
    }

    "deliver events when publisher sends elements and then completes" in {
      new ChainSetup(identity, settings) {
        downstreamSubscription.requestMore(1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.sendComplete()
        downstream.expectNext("test")
        downstream.expectComplete()
      }
    }

    "deliver complete signal when publisher immediately completes" in {
      new ChainSetup(identity, settings) {
        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
    }

    "deliver error signal when publisher immediately fails" in {
      new ChainSetup(identity, settings) {
        object WeirdError extends RuntimeException("weird test exception")
        EventFilter[WeirdError.type](occurrences = 1) intercept {
          upstreamSubscription.sendError(WeirdError)
          downstream.expectError(WeirdError)
        }
      }
    }

    "single subscriber cancels subscription while receiving data" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1)) {
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

  }

  "A Flow with multiple subscribers (FanOutBox)" must {
    "adapt speed to the currently slowest consumer" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(5)
        upstream.expectRequestMore(upstreamSubscription, 1) // because initialInputBufferSize=1

        upstreamSubscription.sendNext("firstElement")
        downstream.expectNext("firstElement")

        upstream.expectRequestMore(upstreamSubscription, 1)
        upstreamSubscription.sendNext("element2")

        downstream.expectNoMsg(1.second)
        downstream2Subscription.requestMore(1)
        downstream2.expectNext("firstElement")

        downstream.expectNext("element2")

        downstream2Subscription.requestMore(1)
        downstream2.expectNext("element2")
      }
    }

    "support slow consumer with fan-out 2" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1, initialFanOutBufferSize = 2, maxFanOutBufferSize = 2)) {
        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.requestMore(5)

        upstream.expectRequestMore(upstreamSubscription, 1) // because initialInputBufferSize=1
        upstreamSubscription.sendNext("element1")
        downstream.expectNext("element1")
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("element2")
        downstream.expectNext("element2")
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("element3")
        // downstream2 has not requested anything, fan-out buffer 2
        downstream.expectNoMsg(100.millis.dilated)

        downstream2Subscription.requestMore(2)
        downstream.expectNext("element3")
        downstream2.expectNext("element1")
        downstream2.expectNext("element2")
        downstream2.expectNoMsg(100.millis.dilated)

        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("element4")
        downstream.expectNext("element4")

        downstream2Subscription.requestMore(2)
        downstream2.expectNext("element3")
        downstream2.expectNext("element4")

        upstreamSubscription.sendComplete()
        downstream.expectComplete()
        downstream2.expectComplete()
      }
    }

    "incoming subscriber while elements were requested before" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
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

    "blocking subscriber cancels subscription" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
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

        downstream.expectNext("element2")
        upstreamSubscription.expectRequestMore(1)
        upstreamSubscription.sendNext("element3")
        downstream2.expectNoMsg(100.millis.dilated)

        downstream.expectNoMsg(200.millis.dilated)
        upstream.expectNoMsg(100.millis.dilated)
        // should unblock fanoutbox
        downstream2Subscription.cancel()
        upstreamSubscription.expectRequestMore(1)
        downstream.expectNext("element3")
        upstreamSubscription.sendNext("element4")
        downstream.expectNext("element4")

        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
    }

    "after initial upstream was completed future subscribers' onComplete should be called instead of onSubscribed" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
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
      new ChainSetup[Int, String](_.map(_ ⇒ throw TestException), settings.copy(initialInputBufferSize = 1, maxFanOutBufferSize = 1)) {
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
        downstream2.expectError() should be(TestException)
      }
    }

    "when all subscriptions were cancelled future subscribers' onError should be called" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1)) {
        upstreamSubscription.expectRequestMore(1)
        downstreamSubscription.cancel()
        upstreamSubscription.expectCancellation()

        val downstream2 = StreamTestKit.consumerProbe[Any]()
        producer.produceTo(downstream2)
        // IllegalStateException shut down
        downstream2.expectError().isInstanceOf[IllegalStateException] should be(true)
      }
    }

    "if an internal error occurs upstream should be cancelled" in pending
    "if an internal error occurs subscribers' onError method should be called" in pending
    "if an internal error occurs future subscribers' onError should be called instead of onSubscribed" in pending

  }

  object TestException extends RuntimeException

}