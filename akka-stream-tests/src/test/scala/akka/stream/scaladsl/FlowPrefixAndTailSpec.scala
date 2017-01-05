/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream._
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class FlowPrefixAndTailSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  "PrefixAndTail" must {

    val testException = new Exception("test") with NoStackTrace

    def newHeadSink = Sink.head[(immutable.Seq[Int], Source[Int, _])]

    "work on empty input" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source.empty.prefixAndTail(10).runWith(futureSink)
      val (prefix, tailFlow) = Await.result(fut, 3.seconds)
      prefix should be(Nil)
      val tailSubscriber = TestSubscriber.manualProbe[Int]
      tailFlow.to(Sink.fromSubscriber(tailSubscriber)).run()
      tailSubscriber.expectSubscriptionAndComplete()
    }

    "work on short input" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source(List(1, 2, 3)).prefixAndTail(10).runWith(futureSink)
      val (prefix, tailFlow) = Await.result(fut, 3.seconds)
      prefix should be(List(1, 2, 3))
      val tailSubscriber = TestSubscriber.manualProbe[Int]
      tailFlow.to(Sink.fromSubscriber(tailSubscriber)).run()
      tailSubscriber.expectSubscriptionAndComplete()
    }

    "work on longer inputs" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(5).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(1 to 5)

      val futureSink2 = Sink.head[immutable.Seq[Int]]
      val fut2 = tail.grouped(6).runWith(futureSink2)
      Await.result(fut2, 3.seconds) should be(6 to 10)
    }

    "handle zero take count" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(0).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Nil)

      val futureSink2 = Sink.head[immutable.Seq[Int]]
      val fut2 = tail.grouped(11).runWith(futureSink2)
      Await.result(fut2, 3.seconds) should be(1 to 10)
    }

    "handle negative take count" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(-1).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Nil)

      val futureSink2 = Sink.head[immutable.Seq[Int]]
      val fut2 = tail.grouped(11).runWith(futureSink2)
      Await.result(fut2, 3.seconds) should be(1 to 10)
    }

    "work if size of take is equal to stream size" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source(1 to 10).prefixAndTail(10).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(1 to 10)

      val subscriber = TestSubscriber.manualProbe[Int]()
      tail.to(Sink.fromSubscriber(subscriber)).run()
      subscriber.expectSubscriptionAndComplete()
    }

    "throw if tail is attempted to be materialized twice" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source(1 to 2).prefixAndTail(1).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Seq(1))

      val subscriber1 = TestSubscriber.probe[Int]()
      tail.to(Sink.fromSubscriber(subscriber1)).run()

      val subscriber2 = TestSubscriber.probe[Int]()
      tail.to(Sink.fromSubscriber(subscriber2)).run()
      subscriber2.expectSubscriptionAndError().getMessage should ===("Substream Source cannot be materialized more than once")

      subscriber1.requestNext(2).expectComplete()

    }

    "signal error if substream has been not subscribed in time" in assertAllStagesStopped {
      val ms = 300

      val tightTimeoutMaterializer =
        ActorMaterializer(ActorMaterializerSettings(system)
          .withSubscriptionTimeoutSettings(
            StreamSubscriptionTimeoutSettings(StreamSubscriptionTimeoutTerminationMode.cancel, ms.millisecond)))

      val futureSink = newHeadSink
      val fut = Source(1 to 2).prefixAndTail(1).runWith(futureSink)(tightTimeoutMaterializer)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Seq(1))

      val subscriber = TestSubscriber.probe[Int]()
      Thread.sleep(1000)

      tail.to(Sink.fromSubscriber(subscriber)).run()(tightTimeoutMaterializer)
      subscriber.expectSubscriptionAndError().getMessage should ===(s"Substream Source has not been materialized in ${ms} milliseconds")
    }
    "not fail the stream if substream has not been subscribed in time and configured subscription timeout is noop" in assertAllStagesStopped {
      val tightTimeoutMaterializer =
        ActorMaterializer(ActorMaterializerSettings(system)
          .withSubscriptionTimeoutSettings(
            StreamSubscriptionTimeoutSettings(StreamSubscriptionTimeoutTerminationMode.noop, 1.millisecond)))

      val futureSink = newHeadSink
      val fut = Source(1 to 2).prefixAndTail(1).runWith(futureSink)(tightTimeoutMaterializer)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Seq(1))

      val subscriber = TestSubscriber.probe[Int]()
      Thread.sleep(200)

      tail.to(Sink.fromSubscriber(subscriber)).run()(tightTimeoutMaterializer)
      subscriber.expectSubscription().request(2)
      subscriber.expectNext(2).expectComplete()
    }

    "shut down main stage if substream is empty, even when not subscribed" in assertAllStagesStopped {
      val futureSink = newHeadSink
      val fut = Source.single(1).prefixAndTail(1).runWith(futureSink)
      val (takes, tail) = Await.result(fut, 3.seconds)
      takes should be(Seq(1))
    }

    "handle onError when no substream open" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[(immutable.Seq[Int], Source[Int, _])]()

      Source.fromPublisher(publisher).prefixAndTail(3).to(Sink.fromSubscriber(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1)

      upstream.expectRequest()
      upstream.sendNext(1)
      upstream.sendError(testException)

      subscriber.expectError(testException)
    }

    "handle onError when substream is open" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[(immutable.Seq[Int], Source[Int, _])]()

      Source.fromPublisher(publisher).prefixAndTail(1).to(Sink.fromSubscriber(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = TestSubscriber.manualProbe[Int]()
      tail.to(Sink.fromSubscriber(substreamSubscriber)).run()
      substreamSubscriber.expectSubscription()

      upstream.sendError(testException)
      substreamSubscriber.expectError(testException)

    }

    "handle master stream cancellation" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[(immutable.Seq[Int], Source[Int, _])]()

      Source.fromPublisher(publisher).prefixAndTail(3).to(Sink.fromSubscriber(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1)

      upstream.expectRequest()
      upstream.sendNext(1)

      downstream.cancel()
      upstream.expectCancellation()
    }

    "handle substream cancellation" in assertAllStagesStopped {
      val publisher = TestPublisher.manualProbe[Int]()
      val subscriber = TestSubscriber.manualProbe[(immutable.Seq[Int], Source[Int, _])]()

      Source.fromPublisher(publisher).prefixAndTail(1).to(Sink.fromSubscriber(subscriber)).run()

      val upstream = publisher.expectSubscription()
      val downstream = subscriber.expectSubscription()

      downstream.request(1000)

      upstream.expectRequest()
      upstream.sendNext(1)

      val (head, tail) = subscriber.expectNext()
      head should be(List(1))
      subscriber.expectComplete()

      val substreamSubscriber = TestSubscriber.manualProbe[Int]()
      tail.to(Sink.fromSubscriber(substreamSubscriber)).run()
      substreamSubscriber.expectSubscription().cancel()

      upstream.expectCancellation()

    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up = TestPublisher.manualProbe[Int]()
      val down = TestSubscriber.manualProbe[(immutable.Seq[Int], Source[Int, _])]()

      val flowSubscriber = Source.asSubscriber[Int].prefixAndTail(1).to(Sink.fromSubscriber(down)).run()

      val downstream = down.expectSubscription()
      downstream.cancel()
      up.subscribe(flowSubscriber)
      val upsub = up.expectSubscription()
      upsub.expectCancellation()
    }

    "work even if tail subscriber arrives after substream completion" in {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      val f = Source.fromPublisher(pub).prefixAndTail(1).runWith(Sink.head)
      val s = pub.expectSubscription()
      s.sendNext(0)

      val (_, tail) = Await.result(f, 3.seconds)

      val tailPub = tail.runWith(Sink.asPublisher(false))
      s.sendComplete()

      tailPub.subscribe(sub)
      sub.expectSubscriptionAndComplete()
    }

  }

}
