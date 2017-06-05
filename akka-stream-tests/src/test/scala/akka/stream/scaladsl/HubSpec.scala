/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, KillSwitches, ThrottleMode }
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.stream.testkit.Utils.{ TE, assertAllStagesStopped }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit.EventFilter

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class HubSpec extends StreamSpec {

  implicit val mat = ActorMaterializer()

  "MergeHub" must {

    "work in the happy case" in assertAllStagesStopped {
      val (sink, result) = MergeHub.source[Int](16).take(20).toMat(Sink.seq)(Keep.both).run()
      Source(1 to 10).runWith(sink)
      Source(11 to 20).runWith(sink)

      result.futureValue.sorted should ===(1 to 20)
    }

    "notify new producers if consumer cancels before first producer" in assertAllStagesStopped {
      val sink = Sink.cancelled[Int].runWith(MergeHub.source[Int](16))
      val upstream = TestPublisher.probe[Int]()

      Source.fromPublisher(upstream).runWith(sink)

      upstream.expectCancellation()
    }

    "notify existing producers if consumer cancels after a few elements" in assertAllStagesStopped {
      val (sink, result) = MergeHub.source[Int](16).take(5).toMat(Sink.seq)(Keep.both).run()
      val upstream = TestPublisher.probe[Int]()

      Source.fromPublisher(upstream).runWith(sink)
      for (i ← 1 to 5) upstream.sendNext(i)

      upstream.expectCancellation()
      result.futureValue.sorted should ===(1 to 5)
    }

    "notify new producers if consumer cancels after a few elements" in assertAllStagesStopped {
      val (sink, result) = MergeHub.source[Int](16).take(5).toMat(Sink.seq)(Keep.both).run()
      val upstream1 = TestPublisher.probe[Int]()
      val upstream2 = TestPublisher.probe[Int]()

      Source.fromPublisher(upstream1).runWith(sink)
      for (i ← 1 to 5) upstream1.sendNext(i)

      upstream1.expectCancellation()
      result.futureValue.sorted should ===(1 to 5)

      Source.fromPublisher(upstream2).runWith(sink)

      upstream2.expectCancellation()
    }

    "respect buffer size" in assertAllStagesStopped {
      val downstream = TestSubscriber.manualProbe[Int]()
      val sink = Sink.fromSubscriber(downstream).runWith(MergeHub.source[Int](3))

      Source(1 to 10).map { i ⇒ testActor ! i; i }.runWith(sink)

      val sub = downstream.expectSubscription()
      sub.request(1)

      // Demand starts from 3
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectNoMsg(100.millis)

      // One element consumed (it was requested), demand 0 remains at producer
      downstream.expectNext(1)

      // Requesting next element, results in next element to be consumed.
      sub.request(1)
      downstream.expectNext(2)

      // Two elements have been consumed, so threshold of 2 is reached, additional 2 demand is dispatched.
      // There is 2 demand at the producer now

      expectMsg(4)
      expectMsg(5)
      expectNoMsg(100.millis)

      // Two additional elements have been sent:
      //  - 3, 4, 5 are pending
      //  - demand is 0 at the producer
      //  - next demand batch is after two elements have been consumed again

      // Requesting next gives the next element
      // Demand is not yet refreshed for the producer as there is one more element until threshold is met
      sub.request(1)
      downstream.expectNext(3)

      expectNoMsg(100.millis)

      sub.request(1)
      downstream.expectNext(4)
      expectMsg(6)
      expectMsg(7)

      sub.cancel()
    }

    "work with long streams" in assertAllStagesStopped {
      val (sink, result) = MergeHub.source[Int](16).take(20000).toMat(Sink.seq)(Keep.both).run()
      Source(1 to 10000).runWith(sink)
      Source(10001 to 20000).runWith(sink)

      result.futureValue.sorted should ===(1 to 20000)
    }

    "work with long streams when buffer size is 1" in assertAllStagesStopped {
      val (sink, result) = MergeHub.source[Int](1).take(20000).toMat(Sink.seq)(Keep.both).run()
      Source(1 to 10000).runWith(sink)
      Source(10001 to 20000).runWith(sink)

      result.futureValue.sorted should ===(1 to 20000)
    }

    "work with long streams when consumer is slower" in assertAllStagesStopped {
      val (sink, result) =
        MergeHub.source[Int](16)
          .take(2000)
          .throttle(10, 1.millisecond, 200, ThrottleMode.shaping)
          .toMat(Sink.seq)(Keep.both)
          .run()

      Source(1 to 1000).runWith(sink)
      Source(1001 to 2000).runWith(sink)

      result.futureValue.sorted should ===(1 to 2000)
    }

    "work with long streams if one of the producers is slower" in assertAllStagesStopped {
      val (sink, result) =
        MergeHub.source[Int](16)
          .take(2000)
          .toMat(Sink.seq)(Keep.both)
          .run()

      Source(1 to 1000).throttle(10, 1.millisecond, 100, ThrottleMode.shaping).runWith(sink)
      Source(1001 to 2000).runWith(sink)

      result.futureValue.sorted should ===(1 to 2000)
    }

    "work with different producers separated over time" in assertAllStagesStopped {
      val downstream = TestSubscriber.probe[immutable.Seq[Int]]()
      val sink = MergeHub.source[Int](16).grouped(100).toMat(Sink.fromSubscriber(downstream))(Keep.left).run()

      Source(1 to 100).runWith(sink)
      downstream.requestNext() should ===(1 to 100)

      Source(101 to 200).runWith(sink)
      downstream.requestNext() should ===(101 to 200)

      downstream.cancel()
    }

    "keep working even if one of the producers fail" in assertAllStagesStopped {
      val (sink, result) = MergeHub.source[Int](16).take(10).toMat(Sink.seq)(Keep.both).run()
      EventFilter.error("Upstream producer failed with exception").intercept {
        Source.failed(TE("failing")).runWith(sink)
        Source(1 to 10).runWith(sink)
      }

      result.futureValue.sorted should ===(1 to 10)

    }

  }

  "BroadcastHub" must {

    "work in the happy case" in assertAllStagesStopped {
      val source = Source(1 to 10).runWith(BroadcastHub.sink(8))
      source.runWith(Sink.seq).futureValue should ===(1 to 10)
    }

    "send the same elements to consumers attaching around the same time" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(2 to 10)).toMat(BroadcastHub.sink(8))(Keep.both).run()

      val f1 = source.runWith(Sink.seq)
      val f2 = source.runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(1))
      f1.futureValue should ===(1 to 10)
      f2.futureValue should ===(1 to 10)
    }

    "send the same prefix to consumers attaching around the same time if one cancels earlier" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(2 to 20)).toMat(BroadcastHub.sink(8))(Keep.both).run()

      val f1 = source.runWith(Sink.seq)
      val f2 = source.take(10).runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(1))
      f1.futureValue should ===(1 to 20)
      f2.futureValue should ===(1 to 10)
    }

    "ensure that subsequent consumers see subsequent elements without gap" in assertAllStagesStopped {
      val source = Source(1 to 20).runWith(BroadcastHub.sink(8))
      source.take(10).runWith(Sink.seq).futureValue should ===(1 to 10)
      source.take(10).runWith(Sink.seq).futureValue should ===(11 to 20)
    }

    "send the same elements to consumers of different speed attaching around the same time" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(2 to 10)).toMat(BroadcastHub.sink(8))(Keep.both).run()

      val f1 = source.throttle(1, 10.millis, 3, ThrottleMode.shaping).runWith(Sink.seq)
      val f2 = source.runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(1))
      f1.futureValue should ===(1 to 10)
      f2.futureValue should ===(1 to 10)
    }

    "send the same elements to consumers of attaching around the same time if the producer is slow" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(2 to 10))
        .throttle(1, 10.millis, 3, ThrottleMode.shaping)
        .toMat(BroadcastHub.sink(8))(Keep.both).run()

      val f1 = source.runWith(Sink.seq)
      val f2 = source.runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(1))
      f1.futureValue should ===(1 to 10)
      f2.futureValue should ===(1 to 10)
    }

    "ensure that from two different speed consumers the slower controls the rate" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(2 to 20)).toMat(BroadcastHub.sink(1))(Keep.both).run()

      val f1 = source.throttle(1, 10.millis, 1, ThrottleMode.shaping).runWith(Sink.seq)
      // Second cannot be overwhelmed since the first one throttles the overall rate, and second allows a higher rate
      val f2 = source.throttle(10, 10.millis, 8, ThrottleMode.enforcing).runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(1))
      f1.futureValue should ===(1 to 20)
      f2.futureValue should ===(1 to 20)

    }

    "send the same elements to consumers attaching around the same time with a buffer size of one" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(2 to 10)).toMat(BroadcastHub.sink(1))(Keep.both).run()

      val f1 = source.runWith(Sink.seq)
      val f2 = source.runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(1))
      f1.futureValue should ===(1 to 10)
      f2.futureValue should ===(1 to 10)
    }

    "be able to implement a keep-dropping-if-unsubscribed policy with a simple Sink.ignore" in assertAllStagesStopped {
      val killSwitch = KillSwitches.shared("test-switch")
      val source = Source.fromIterator(() ⇒ Iterator.from(0)).via(killSwitch.flow).runWith(BroadcastHub.sink(8))

      // Now the Hub "drops" elements until we attach a new consumer (Source.ignore consumes as fast as possible)
      source.runWith(Sink.ignore)

      // Now we attached a subscriber which will block the Sink.ignore to "take away" and drop elements anymore,
      // turning the BroadcastHub to a normal non-dropping mode
      val downstream = TestSubscriber.probe[Int]()
      source.runWith(Sink.fromSubscriber(downstream))

      downstream.request(1)
      val first = downstream.expectNext()

      for (i ← (first + 1) to (first + 10)) {
        downstream.request(1)
        downstream.expectNext(i)
      }

      downstream.cancel()

      killSwitch.shutdown()
    }

    "properly signal error to consumers" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val source = Source.fromPublisher(upstream).runWith(BroadcastHub.sink(8))

      val downstream1 = TestSubscriber.probe[Int]()
      val downstream2 = TestSubscriber.probe[Int]()
      source.runWith(Sink.fromSubscriber(downstream1))
      source.runWith(Sink.fromSubscriber(downstream2))

      downstream1.request(4)
      downstream2.request(8)

      (1 to 8) foreach (upstream.sendNext(_))

      downstream1.expectNext(1, 2, 3, 4)
      downstream2.expectNext(1, 2, 3, 4, 5, 6, 7, 8)

      downstream1.expectNoMsg(100.millis)
      downstream2.expectNoMsg(100.millis)

      upstream.sendError(TE("Failed"))

      downstream1.expectError(TE("Failed"))
      downstream2.expectError(TE("Failed"))
    }

    "properly signal completion to consumers arriving after producer finished" in assertAllStagesStopped {
      val source = Source.empty[Int].runWith(BroadcastHub.sink(8))
      // Wait enough so the Hub gets the completion. This is racy, but this is fine because both
      // cases should work in the end
      Thread.sleep(10)

      source.runWith(Sink.seq).futureValue should ===(Nil)
    }

    "remember completion for materialisations after completion" in {

      val (sourceProbe, source) = TestSource.probe[Unit].toMat(BroadcastHub.sink)(Keep.both).run()
      val sinkProbe = source.runWith(TestSink.probe[Unit])

      sourceProbe.sendComplete()

      sinkProbe.request(1)
      sinkProbe.expectComplete()

      // Materialize a second time. There was a race here, where we managed to enqueue our Source registration just
      // immediately before the Hub shut down.
      val sink2Probe = source.runWith(TestSink.probe[Unit])

      sink2Probe.request(1)
      sink2Probe.expectComplete()
    }

    "properly signal error to consumers arriving after producer finished" in assertAllStagesStopped {
      val source = Source.failed(TE("Fail!")).runWith(BroadcastHub.sink(8))
      // Wait enough so the Hub gets the completion. This is racy, but this is fine because both
      // cases should work in the end
      Thread.sleep(10)

      a[TE] shouldBe thrownBy {
        Await.result(source.runWith(Sink.seq), 3.seconds)
      }
    }

  }

  "PartitionHub" must {

    "work in the happy case with one stream" in assertAllStagesStopped {
      val source = Source(1 to 10).runWith(PartitionHub.sink((size, elem) ⇒ 0, startAfterNbrOfConsumers = 0, bufferSize = 8))
      source.runWith(Sink.seq).futureValue should ===(1 to 10)
    }

    "work in the happy case with two streams" in assertAllStagesStopped {
      val source = Source(0 until 10).runWith(PartitionHub.sink((size, elem) ⇒ elem % size, startAfterNbrOfConsumers = 2, bufferSize = 8))
      val result1 = source.runWith(Sink.seq)
      // it should not start publishing until startAfterNbrOfConsumers = 2
      Thread.sleep(20)
      val result2 = source.runWith(Sink.seq)
      result1.futureValue should ===(0 to 8 by 2)
      result2.futureValue should ===(1 to 9 by 2)
    }

    "be able to use as round-robin router" in assertAllStagesStopped {
      val source = Source(0 until 10).runWith(PartitionHub.statefulSink(() ⇒ {
        var n = 0L

        (info, elem) ⇒ {
          n += 1
          info.consumerIds((n % info.size).toInt)
        }
      }, startAfterNbrOfConsumers = 2, bufferSize = 8))
      val result1 = source.runWith(Sink.seq)
      val result2 = source.runWith(Sink.seq)
      result1.futureValue should ===(1 to 9 by 2)
      result2.futureValue should ===(0 to 8 by 2)
    }

    "be able to use as sticky session router" in assertAllStagesStopped {
      val source = Source(List("usr-1", "usr-2", "usr-1", "usr-3")).runWith(PartitionHub.statefulSink(() ⇒ {
        var sessions = Map.empty[String, Long]
        var n = 0L

        (info, elem) ⇒ {
          sessions.get(elem) match {
            case Some(id) if info.consumerIds.exists(_ == id) ⇒ id
            case _ ⇒
              n += 1
              val id = info.consumerIds((n % info.consumerIds.length).toInt)
              sessions = sessions.updated(elem, id)
              id
          }
        }
      }, startAfterNbrOfConsumers = 2, bufferSize = 8))
      val result1 = source.runWith(Sink.seq)
      val result2 = source.runWith(Sink.seq)
      result1.futureValue should ===(List("usr-2"))
      result2.futureValue should ===(List("usr-1", "usr-1", "usr-3"))
    }

    "be able to use as fastest consumer router" in assertAllStagesStopped {
      val source = Source(0 until 1000).runWith(PartitionHub.statefulSink(
        () ⇒ (info, elem) ⇒ info.consumerIds.toVector.minBy(id ⇒ info.queueSize(id)),
        startAfterNbrOfConsumers = 2, bufferSize = 4))
      val result1 = source.runWith(Sink.seq)
      val result2 = source.throttle(10, 100.millis, 10, ThrottleMode.Shaping).runWith(Sink.seq)

      result1.futureValue.size should be > (result2.futureValue.size)
    }

    "route evenly" in assertAllStagesStopped {
      val (testSource, hub) = TestSource.probe[Int].toMat(
        PartitionHub.sink((size, elem) ⇒ elem % size, startAfterNbrOfConsumers = 2, bufferSize = 8))(Keep.both).run()
      val probe0 = hub.runWith(TestSink.probe[Int])
      val probe1 = hub.runWith(TestSink.probe[Int])
      probe0.request(3)
      probe1.request(10)
      testSource.sendNext(0)
      probe0.expectNext(0)
      testSource.sendNext(1)
      probe1.expectNext(1)

      testSource.sendNext(2)
      testSource.sendNext(3)
      testSource.sendNext(4)
      probe0.expectNext(2)
      probe1.expectNext(3)
      probe0.expectNext(4)

      // probe1 has not requested more
      testSource.sendNext(5)
      testSource.sendNext(6)
      testSource.sendNext(7)
      probe1.expectNext(5)
      probe1.expectNext(7)
      probe0.expectNoMsg(10.millis)
      probe0.request(10)
      probe0.expectNext(6)

      testSource.sendComplete()
      probe0.expectComplete()
      probe1.expectComplete()
    }

    "route unevenly" in assertAllStagesStopped {
      val (testSource, hub) = TestSource.probe[Int].toMat(
        PartitionHub.sink((size, elem) ⇒ (elem % 3) % 2, startAfterNbrOfConsumers = 2, bufferSize = 8))(Keep.both).run()
      val probe0 = hub.runWith(TestSink.probe[Int])
      val probe1 = hub.runWith(TestSink.probe[Int])

      // (_ % 3) % 2
      // 0 => 0
      // 1 => 1
      // 2 => 0
      // 3 => 0
      // 4 => 1

      probe0.request(10)
      probe1.request(10)
      testSource.sendNext(0)
      probe0.expectNext(0)
      testSource.sendNext(1)
      probe1.expectNext(1)
      testSource.sendNext(2)
      probe0.expectNext(2)
      testSource.sendNext(3)
      probe0.expectNext(3)
      testSource.sendNext(4)
      probe1.expectNext(4)

      testSource.sendComplete()
      probe0.expectComplete()
      probe1.expectComplete()
    }

    "backpressure" in assertAllStagesStopped {
      val (testSource, hub) = TestSource.probe[Int].toMat(
        PartitionHub.sink((size, elem) ⇒ 0, startAfterNbrOfConsumers = 2, bufferSize = 4))(Keep.both).run()
      val probe0 = hub.runWith(TestSink.probe[Int])
      val probe1 = hub.runWith(TestSink.probe[Int])
      probe0.request(10)
      probe1.request(10)
      testSource.sendNext(0)
      probe0.expectNext(0)
      testSource.sendNext(1)
      probe0.expectNext(1)
      testSource.sendNext(2)
      probe0.expectNext(2)
      testSource.sendNext(3)
      probe0.expectNext(3)
      testSource.sendNext(4)
      probe0.expectNext(4)

      testSource.sendComplete()
      probe0.expectComplete()
      probe1.expectComplete()
    }

    "ensure that from two different speed consumers the slower controls the rate" in assertAllStagesStopped {
      val (firstElem, source) = Source.maybe[Int].concat(Source(1 until 20)).toMat(
        PartitionHub.sink((size, elem) ⇒ elem % size, startAfterNbrOfConsumers = 2, bufferSize = 1))(Keep.both).run()

      val f1 = source.throttle(1, 10.millis, 1, ThrottleMode.shaping).runWith(Sink.seq)
      // Second cannot be overwhelmed since the first one throttles the overall rate, and second allows a higher rate
      val f2 = source.throttle(10, 10.millis, 8, ThrottleMode.enforcing).runWith(Sink.seq)

      // Ensure subscription of Sinks. This is racy but there is no event we can hook into here.
      Thread.sleep(100)
      firstElem.success(Some(0))
      f1.futureValue should ===(0 to 18 by 2)
      f2.futureValue should ===(1 to 19 by 2)

    }

    "properly signal error to consumers" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val source = Source.fromPublisher(upstream).runWith(
        PartitionHub.sink((size, elem) ⇒ elem % size, startAfterNbrOfConsumers = 2, bufferSize = 8))

      val downstream1 = TestSubscriber.probe[Int]()
      source.runWith(Sink.fromSubscriber(downstream1))
      val downstream2 = TestSubscriber.probe[Int]()
      source.runWith(Sink.fromSubscriber(downstream2))

      downstream1.request(4)
      downstream2.request(8)

      (0 until 16) foreach (upstream.sendNext(_))

      downstream1.expectNext(0, 2, 4, 6)
      downstream2.expectNext(1, 3, 5, 7, 9, 11, 13, 15)

      downstream1.expectNoMsg(100.millis)
      downstream2.expectNoMsg(100.millis)

      upstream.sendError(TE("Failed"))

      downstream1.expectError(TE("Failed"))
      downstream2.expectError(TE("Failed"))
    }

    "properly signal completion to consumers arriving after producer finished" in assertAllStagesStopped {
      val source = Source.empty[Int].runWith(PartitionHub.sink((size, elem) ⇒ elem % size, startAfterNbrOfConsumers = 0))
      // Wait enough so the Hub gets the completion. This is racy, but this is fine because both
      // cases should work in the end
      Thread.sleep(10)

      source.runWith(Sink.seq).futureValue should ===(Nil)
    }

    "remember completion for materialisations after completion" in {

      val (sourceProbe, source) = TestSource.probe[Unit].toMat(
        PartitionHub.sink((size, elem) ⇒ 0, startAfterNbrOfConsumers = 0))(Keep.both).run()
      val sinkProbe = source.runWith(TestSink.probe[Unit])

      sourceProbe.sendComplete()

      sinkProbe.request(1)
      sinkProbe.expectComplete()

      // Materialize a second time. There was a race here, where we managed to enqueue our Source registration just
      // immediately before the Hub shut down.
      val sink2Probe = source.runWith(TestSink.probe[Unit])

      sink2Probe.request(1)
      sink2Probe.expectComplete()
    }

    "properly signal error to consumers arriving after producer finished" in assertAllStagesStopped {
      val source = Source.failed[Int](TE("Fail!")).runWith(
        PartitionHub.sink((size, elem) ⇒ 0, startAfterNbrOfConsumers = 0))
      // Wait enough so the Hub gets the failure. This is racy, but this is fine because both
      // cases should work in the end
      Thread.sleep(10)

      a[TE] shouldBe thrownBy {
        Await.result(source.runWith(Sink.seq), 3.seconds)
      }
    }

  }

}
