/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.collection.immutable
import akka.stream.impl.EmptyPublisher
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import org.reactivestreams.Publisher
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.MaterializerSettings
import akka.stream.testkit.StreamTestKit.SubscriberProbe

class FlowPrefixAndTailSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = FlowMaterializer(settings)

  "PrefixAndTail" must {

    val testException = new Exception("test") with NoStackTrace

    def newFutureSink = FutureSink[(immutable.Seq[Int], FlowWithSource[Int, Int])]

    "work on empty input" in {
      val futureSink = newFutureSink
      val mf = FlowFrom(Nil).prefixAndTail(10).withSink(futureSink).run()
      val fut = futureSink.future(mf)
      val (prefix, tailFlow) = Await.result(fut, 3.seconds)
      prefix should be(Nil)
      val tailSubscriber = SubscriberProbe[Int]
      tailFlow.publishTo(tailSubscriber)
      tailSubscriber.expectComplete()
    }

    "work on short input" in {
      val futureSink = newFutureSink
      val mf = FlowFrom(List(1, 2, 3)).prefixAndTail(10).withSink(futureSink).run()
      val fut = futureSink.future(mf)
      val (prefix, tailFlow) = Await.result(fut, 3.seconds)
      prefix should be(List(1, 2, 3))
      val tailSubscriber = SubscriberProbe[Int]
      tailFlow.publishTo(tailSubscriber)
      tailSubscriber.expectComplete()
    }

    "work on longer inputs" in {
      val futureSink = newFutureSink
      val mf = FlowFrom((1 to 10).iterator).prefixAndTail(5).withSink(futureSink).run()
      val fut = futureSink.future(mf)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(1 to 5)

      val futureSink2 = FutureSink[immutable.Seq[Int]]
      val mf2 = tail.grouped(6).withSink(futureSink2).run()
      val fut2 = futureSink2.future(mf2)
      Await.result(fut2, 3.seconds) should be(6 to 10)
    }

    "handle zero take count" in {
      val futureSink = newFutureSink
      val mf = FlowFrom((1 to 10).iterator).prefixAndTail(0).withSink(futureSink).run()
      val fut = futureSink.future(mf)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Nil)

      val futureSink2 = FutureSink[immutable.Seq[Int]]
      val mf2 = tail.grouped(11).withSink(futureSink2).run()
      val fut2 = futureSink2.future(mf2)
      Await.result(fut2, 3.seconds) should be(1 to 10)
    }

    "handle negative take count" in {
      val futureSink = newFutureSink
      val mf = FlowFrom((1 to 10).iterator).prefixAndTail(-1).withSink(futureSink).run()
      val fut = futureSink.future(mf)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Nil)

      val futureSink2 = FutureSink[immutable.Seq[Int]]
      val mf2 = tail.grouped(11).withSink(futureSink2).run()
      val fut2 = futureSink2.future(mf2)
      Await.result(fut2, 3.seconds) should be(1 to 10)
    }

    "work if size of take is equal to stream size" in {
      val futureSink = newFutureSink
      val mf = FlowFrom((1 to 10).iterator).prefixAndTail(10).withSink(futureSink).run()
      val fut = futureSink.future(mf)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(1 to 10)

      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      tail.publishTo(subscriber)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "handle onError when no substream open" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], FlowWithSource[Int, Int])]()

      FlowFrom(publisher).prefixAndTail(3).publishTo(subscriber)

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
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], FlowWithSource[Int, Int])]()

      FlowFrom(publisher).prefixAndTail(1).publishTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = StreamTestKit.SubscriberProbe[Int]()
      tail.publishTo(substreamSubscriber)
      substreamSubscriber.expectSubscription()

      upstream.sendError(testException)
      substreamSubscriber.expectError(testException)

    }

    "handle master stream cancellation" in {
      val publisher = StreamTestKit.PublisherProbe[Int]()
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], FlowWithSource[Int, Int])]()

      FlowFrom(publisher).prefixAndTail(3).publishTo(subscriber)

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
      val subscriber = StreamTestKit.SubscriberProbe[(immutable.Seq[Int], FlowWithSource[Int, Int])]()

      FlowFrom(publisher).prefixAndTail(1).publishTo(subscriber)

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = StreamTestKit.SubscriberProbe[Int]()
      tail.publishTo(substreamSubscriber)
      substreamSubscriber.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }

}
