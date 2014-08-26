/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.impl.EmptyPublisher
import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import org.reactivestreams.Publisher

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlowPrefixAndTailSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "PrefixAndTail" must {

    val testException = new Exception("test") with NoStackTrace

    "work on empty input" in {
      Await.result(Flow(Nil).prefixAndTail(10).toFuture(), 3.seconds) should be((Nil, EmptyPublisher))
    }

    "work on short input" in {
      Await.result(Flow(List(1, 2, 3)).prefixAndTail(10).toFuture(), 3.seconds) should be((List(1, 2, 3), EmptyPublisher))
    }

    "work on longer inputs" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(5).toFuture(), 3.seconds)
      takes should be(1 to 5)
      Await.result(Flow(tail).grouped(6).toFuture(), 3.seconds) should be(6 to 10)
    }

    "handle zero take count" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(0).toFuture(), 3.seconds)
      takes should be(Nil)
      Await.result(Flow(tail).grouped(11).toFuture(), 3.seconds) should be(1 to 10)
    }

    "handle negative take count" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(-1).toFuture(), 3.seconds)
      takes should be(Nil)
      Await.result(Flow(tail).grouped(11).toFuture(), 3.seconds) should be(1 to 10)
    }

    "work if size of take is equals to stream size" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(10).toFuture(), 3.seconds)
      takes should be(1 to 10)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      Flow(tail).produceTo(subscriber)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "handle onError when no substream open" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(Seq[Int], Publisher[Int])]()

      Flow(publisher).prefixAndTail(3).produceTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1)

      upstream.expectRequest()
      upstream.sendNext(1)
      upstream.sendError(testException)

      subscriber.expectError(testException)
    }

    "handle onError when substream is open" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(Seq[Int], Publisher[Int])]()

      Flow(publisher).prefixAndTail(1).produceTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = StreamTestKit.SubscriberProbe[Int]()
      Flow(tail).produceTo(substreamSubscriber)
      substreamSubscriber.expectSubscription()

      upstream.sendError(testException)
      substreamSubscriber.expectError(testException)

    }

    "handle master stream cancellation" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(Seq[Int], Publisher[Int])]()

      Flow(publisher).prefixAndTail(3).produceTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1)

      upstream.expectRequest()
      upstream.sendNext(1)

      downstream.cancel()
      upstream.expectCancellation()
    }

    "handle substream cancellation" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(Seq[Int], Publisher[Int])]()

      Flow(publisher).prefixAndTail(1).produceTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = StreamTestKit.SubscriberProbe[Int]()
      Flow(tail).produceTo(substreamSubscriber)
      substreamSubscriber.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }

}
