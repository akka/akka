/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.StreamTestKit.SubscriberProbe

class FlowPrefixAndTailSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "PrefixAndTail" must {

    val testException = new Exception("test") with NoStackTrace

    def newHeadSink = Sink.head[(immutable.Seq[Int], Source[Int])]

    "work on empty input" in {
      val futureSink = newHeadSink
      val fut = Source.empty.prefixAndTail(10).runWith(futureSink)
      val (prefix, tailFlow) = Await.result(fut, 3.seconds)
      prefix should be(Nil)
      val tailSubscriber = SubscriberProbe[Int]
      tailFlow.to(Sink(tailSubscriber)).run()
      tailSubscriber.expectComplete()
    }

    "work on short input" in {
      val futureSink = newHeadSink
      val fut = Source(List(1, 2, 3)).prefixAndTail(10).runWith(futureSink)
      val (prefix, tailFlow) = Await.result(fut, 3.seconds)
      prefix should be(List(1, 2, 3))
      val tailSubscriber = SubscriberProbe[Int]
      tailFlow.to(Sink(tailSubscriber)).run()
      tailSubscriber.expectComplete()
    }

    "work on longer inputs" in {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(5).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(1 to 5)

      val futureSink2 = Sink.head[immutable.Seq[Int]]
      val fut2 = tail.grouped(6).runWith(futureSink2)
      Await.result(fut2, 3.seconds) should be(6 to 10)
    }

    "handle zero take count" in {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(0).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Nil)

      val futureSink2 = Sink.head[immutable.Seq[Int]]
      val fut2 = tail.grouped(11).runWith(futureSink2)
      Await.result(fut2, 3.seconds) should be(1 to 10)
    }

    "handle negative take count" in {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(-1).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Nil)

      val futureSink2 = Sink.head[immutable.Seq[Int]]
      val fut2 = tail.grouped(11).runWith(futureSink2)
      Await.result(fut2, 3.seconds) should be(1 to 10)
    }

    "work if size of take is equal to stream size" in {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(10).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(1 to 10)

      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      tail.to(Sink(subscriber)).run()
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "handle onError when no substream open" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], Source[Int])]()

      Source(publisher).prefixAndTail(3).to(Sink(subscriber)).run()

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
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], Source[Int])]()

      Source(publisher).prefixAndTail(1).to(Sink(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = StreamTestKit.SubscriberProbe[Int]()
      tail.to(Sink(substreamSubscriber)).run()
      substreamSubscriber.expectSubscription()

      upstream.sendError(testException)
      substreamSubscriber.expectError(testException)

    }

    "handle master stream cancellation" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], Source[Int])]()

      Source(publisher).prefixAndTail(3).to(Sink(subscriber)).run()

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
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], Source[Int])]()

      Source(publisher).prefixAndTail(1).to(Sink(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = StreamTestKit.SubscriberProbe[Int]()
      tail.to(Sink(substreamSubscriber)).run()
      substreamSubscriber.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }

}
