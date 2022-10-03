/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ ActorAttributes, Supervision }
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ TestLatch, TestProbe }
import org.scalatest.compatible.Assertion

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Left, Right }

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue

class FlowMapAsyncPartitionedSpec extends StreamSpec {
  import Utils.TE

  "A Flow with mapAsyncPartitioned" must {
    "produce future elements" in {
      import system.dispatcher

      val c = TestSubscriber.manualProbe[Int]()
      Source(1 to 3)
        .mapAsyncPartitioned(4, 2)(_ % 2) { (elem, _) =>
          Future(elem)
        }
        .runWith(Sink.fromSubscriber(c))

      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMessage(200.millis)
      sub.request(2)
      c.expectNext(3)
      c.expectComplete()
    }
  }

  "produce elements in order of ingestion regardless of future completion order" in {
    val c = TestSubscriber.manualProbe[Int]()
    val promises = (0 until 50).map { _ =>
      Promise[Int]()
    }.toArray

    Source(0 until 50)
      .mapAsyncPartitioned(25, 3)(_ % 7) { (elem, _) =>
        promises(elem).future
      }
      .to(Sink.fromSubscriber(c))
      .run()

    val sub = c.expectSubscription()
    sub.request(1000)

    // completes all the promises in random order
    @annotation.tailrec
    def iter(completed: Int, i: Int): Unit =
      if (completed != promises.size) {
        if (promises(i).isCompleted) iter(completed, (i + 1) % promises.size)
        else {
          promises(i).success(i)
          iter(completed + 1, scala.util.Random.nextInt(promises.size))
        }
      }

    iter(0, scala.util.Random.nextInt(promises.size))

    (0 until 50).foreach { i =>
      c.expectNext(i)
    }
    c.expectComplete()
  }

  "not run more futures than overall parallelism" in {
    import system.dispatcher

    val probe = TestProbe()
    val c = TestSubscriber.manualProbe[Int]()

    // parallelism, perPartition, and partitioner chosen to maximize number of futures
    //  in-flight
    val maxParallelism = (Runtime.getRuntime.availableProcessors / 2).max(3).min(8)
    Source(1 to 22)
      .mapAsyncPartitioned(maxParallelism, 2)(_ % 22) { (elem, _) =>
        Future {
          probe.ref ! elem
          elem
        }
      }
      .to(Sink.fromSubscriber(c))
      .run()

    val sub = c.expectSubscription()
    probe.expectNoMessage(100.millis)
    sub.request(1)
    probe.receiveN(maxParallelism + 1) should contain theSameElementsAs (1 to (maxParallelism + 1))
    probe.expectNoMessage(100.millis)
    sub.request(2)
    probe.receiveN(2) should contain theSameElementsAs (2 to 3).map(_ + maxParallelism)
    probe.expectNoMessage(100.millis)
    sub.request(10)
    probe.receiveN(10) should contain theSameElementsAs (4 to 13).map(_ + maxParallelism)
    probe.expectNoMessage(200.millis)
  }

  "not backpressure based on perPartition limit" in {
    val promises = Array.fill(10)(Promise[Int]())
    val probes = Array.fill(10)(TestProbe())

    val sinkProbe =
      Source(0 until 10)
        .mapAsyncPartitioned(10, 1)(_ < 9) { (elem, _) =>
          probes(elem).ref ! elem
          promises(elem).future
        }
        .runWith(TestSink())

    sinkProbe.expectSubscription().request(10)
    probes(0).expectMsg(0) // true partition
    probes(9).expectMsg(9) // false partition
    // all in the true partition, but should not be started
    (1 until 9).foreach { x =>
      probes(x).expectNoMessage(10.millis)
    }

    // complete promises in reverse order
    (1 to 9).foreach { negOff =>
      promises(10 - negOff).success(negOff)
      sinkProbe.expectNoMessage(10.millis)
    }

    promises(0).success(10)
    sinkProbe.toStrict(100.millis) should contain theSameElementsAs (1 to 10)
  }

  "signal future already failed" in {
    import system.dispatcher

    val latch = TestLatch(1)
    val c = TestSubscriber.manualProbe[Int]()
    Source(1 to 5)
      .mapAsyncPartitioned(4, 2)(_ % 3) { (elem, _) =>
        if (elem == 3) Future.failed[Int](new TE("BOOM TRES!"))
        else
          Future {
            Await.ready(latch, 10.seconds)
            elem
          }
      }
      .to(Sink.fromSubscriber(c))
      .run()

    val sub = c.expectSubscription()
    sub.request(10)
    c.expectError().getMessage shouldBe "BOOM TRES!"
    latch.countDown()
  }

  "signal future failure" in {
    import system.dispatcher

    val latch = TestLatch(1)
    val c = TestSubscriber.manualProbe[Int]()

    Source(1 to 5)
      .mapAsyncPartitioned(4, 2)(_ % 3) { (elem, _) =>
        Future {
          if (elem == 3) throw new TE("BOOM TROIS!")
          else {
            Await.ready(latch, 10.seconds)
            elem
          }
        }
      }
      .to(Sink.fromSubscriber(c))
      .run()

    val sub = c.expectSubscription()
    sub.request(10)
    c.expectError().getMessage shouldBe "BOOM TROIS!"
    latch.countDown()
  }

  "signal future failure asap" in {
    val latch = TestLatch(1)
    val done =
      Source(1 to 5)
        .map { elem =>
          if (elem == 1) elem
          else {
            // Slow the upstream after the first
            Await.ready(latch, 10.seconds)
            elem
          }
        }
        .mapAsyncPartitioned(4, 2)(_ % 3) { (elem, _) =>
          if (elem == 1) Future.failed(new TE("BOOM EIN!"))
          else Future.successful(elem)
        }
        .runWith(Sink.ignore)

    intercept[TE] {
      Await.result(done, remainingOrDefault)
    }.getMessage shouldBe "BOOM EIN!"
    latch.countDown()
  }

  "fail ASAP midstream when stop supervision is in place" in {
    import scala.collection.immutable
    import system.dispatcher

    val promises = (0 until 6).map(_ => Promise[Int]()).toArray
    val probe =
      Source(0 until 6)
        .mapAsyncPartitioned(5, 1)(_ % 7) { (elem, _) =>
          promises(elem).future.map(n => ('A' + n).toChar)
        }
        .runWith(TestSink())

    probe.request(100)
    val failure = new Exception("BOOM två")
    scala.util.Random.shuffle((0 until 6): immutable.Seq[Int]).foreach { n =>
      if (n == 2) promises(n).failure(failure)
      else promises(n).success(n)
    }

    // we don't know when the third promise will be failed
    probe.expectNextOrError() match {
      case Left(ex) => ex.getMessage shouldBe failure.getMessage // fine, error can overtake elements
      case Right('A') =>
        probe.expectNextOrError() match {
          case Left(ex) => ex.getMessage shouldBe failure.getMessage // fine, error can overtake elements
          case Right('B') =>
            probe.expectNextOrError() match {
              case Left(ex) => ex.getMessage shouldBe failure.getMessage // fine, error can overtake elements
              case Right(n) => fail(s"stage should have failed rather than emit $n")
            }

          case unexpected => fail(s"unexpected $unexpected")
        }

      case unexpected => fail(s"unexpected $unexpected")
    }
  }

  "drop failed elements midstream when resume supervision is in place" in {
    import scala.collection.immutable
    import system.dispatcher

    val promises = (0 until 6).map(_ => Promise[Int]()).toArray
    val elements =
      Source(0 until 6)
        .mapAsyncPartitioned(5, 1)(_ % 7) { (elem, _) =>
          promises(elem).future.map(n => ('A' + n).toChar)
        }
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.seq)

    val failure = new Exception("BOOM TWEE!")
    scala.util.Random.shuffle((0 until 6): immutable.Seq[Int]).foreach { n =>
      if (n == 2) promises(n).failure(failure)
      else promises(n).success(n)
    }

    elements.futureValue should contain theSameElementsInOrderAs "ABDEF"
  }

  "signal error when constructing future" in {
    import system.dispatcher

    val latch = TestLatch(1)
    val c = TestSubscriber.manualProbe[Int]()

    Source(1 to 5)
      .mapAsyncPartitioned(4, 2)(_ % 2) { (elem, _) =>
        if (elem == 3) throw new TE("BOOM TRE!")
        else
          Future {
            Await.ready(latch, 10.seconds)
            elem
          }
      }
      .to(Sink.fromSubscriber(c))
      .run()

    val sub = c.expectSubscription()
    sub.request(10)
    c.expectError().getMessage shouldBe "BOOM TRE!"
    latch.countDown()
  }

  "resume after failed future if resume supervision is in place" in {
    import system.dispatcher

    val elements =
      Source(1 to 5)
        .mapAsyncPartitioned(4, 1)(_ % 2) { (elem, _) =>
          Future {
            if (elem == 3) throw new TE("BOOM TRZY!")
            else elem
          }
        }
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.seq)

    elements.futureValue should contain theSameElementsInOrderAs Seq(1, 2, 4, 5)
  }

  "resume after already failed future if resume supervision is in place" in {
    val expected =
      Source(1 to 5)
        .mapAsyncPartitioned(4, 2)(_ % 2) { (elem, _) =>
          if (elem == 3) Future.failed(new TE("BOOM TRI!"))
          else Future.successful(elem)
        }
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.seq)

    expected.futureValue should contain theSameElementsInOrderAs Seq(1, 2, 4, 5)
  }

  "resume after multiple failures if resume supervision is in place" in {
    import system.dispatcher

    val expected =
      Source(1 to 10)
        .mapAsyncPartitioned(4, 2)(_ % 3) { (elem, _) =>
          if (elem % 4 < 3) {
            val ex = new TE("BOOM!")
            scala.util.Random.nextInt(3) match {
              case 0 => Future.failed(ex)
              case 1 => Future { throw ex }
              case 2 => throw ex
            }
          } else Future.successful(elem)
        }
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.seq)

    expected.futureValue should contain theSameElementsInOrderAs (1 to 10).filter(_ % 4 == 3)
  }

  "ignore null-completed futures" in {
    val shouldBeNull = {
      val n = scala.util.Random.nextInt(10) + 1
      (1 to n).foldLeft(Set.empty[Int]) { (set, _) =>
        set + scala.util.Random.nextInt(10)
      }
    }
    if (shouldBeNull.isEmpty) fail("should be at least one null")

    val f: (Int, Int) => Future[String] = { (elem, _) =>
      if (shouldBeNull(elem)) Future.successful(null)
      else Future.successful(elem.toString)
    }

    val result =
      Source(1 to 10).mapAsyncPartitioned(5, 2)(_ % 2)(f).runWith(Sink.seq)

    result.futureValue should contain theSameElementsInOrderAs (1 to 10).filterNot(shouldBeNull).map(_.toString)
  }

  "handle cancel properly" in {
    val pub = TestPublisher.manualProbe[Int]()
    val sub = TestSubscriber.manualProbe[Int]()

    Source
      .fromPublisher(pub)
      .mapAsyncPartitioned(4, 2)(_ % 2) { (elem, _) =>
        Future.successful(elem)
      }
      .runWith(Sink.fromSubscriber(sub))

    val upstream = pub.expectSubscription()
    upstream.expectRequest()

    sub.expectSubscription().cancel()

    upstream.expectCancellation()
  }

  val maxParallelism = Runtime.getRuntime.availableProcessors.max(8)
  val perPartition = scala.util.Random.nextInt(maxParallelism - 1) + 1
  val numPartitions = 1 + (maxParallelism / perPartition)

  s"not run more futures than allowed overall or per-partition ($maxParallelism, $perPartition, $numPartitions)" in {
    val partitioner: Int => Int = _ % (1 + (maxParallelism / perPartition))

    val globalCounter = new AtomicInteger
    val partitionCounters =
      (0 until numPartitions).map(_ -> new AtomicInteger).toMap: Map[Int, AtomicInteger]
    val queue = new LinkedBlockingQueue[(Int, Promise[Int], Long)]

    val timer = new Thread {
      val maxDelay = 100000 // nanos

      def delay(): Int =
        scala.util.Random.nextInt(maxDelay) + 1

      var count = 0

      @annotation.tailrec
      final override def run(): Unit = {
        val cont =
          try {
            val (partition, promise, enqueued) = queue.take()
            val wakeup = enqueued + delay()
            while (System.nanoTime() < wakeup) {}
            globalCounter.decrementAndGet()
            partitionCounters(partition).decrementAndGet()
            promise.success(count)
            count += 1
            true
          } catch {
            case _: InterruptedException => false
          }

        if (cont) run()
      }
    }
    timer.start()

    def deferred(partition: Int): Future[Int] =
      if (globalCounter.incrementAndGet() > maxParallelism) {
        Future.failed(new AssertionError("global parallelism exceeded"))
      } else if (partitionCounters(partition).incrementAndGet() > perPartition) {
        Future.failed(new AssertionError(s"partition parallelism for partition $partition exceeded"))
      } else {
        val p = Promise[Int]()
        queue.offer((partition, p, System.nanoTime()))
        p.future
      }

    try {
      @volatile var lastElem = 0
      val successes =
        Source(1 to 10000)
          .mapAsyncPartitioned(maxParallelism, perPartition)(partitioner) { (_, partition) =>
            deferred(partition)
          }
          .filter { elem =>
            if (elem == lastElem + 1) {
              lastElem = elem
              true
            } else false
          }
          .runFold(0) { (c, e) =>
            c + e
          }

      successes.futureValue shouldBe (5000 * 9999)
    } finally {
      timer.interrupt()
    }
  }

  val thisWillNotStand = TE("this will not stand")

  def deciderTest(f: (Boolean, Boolean) => Future[Boolean]): Assertion = {
    val failCount = new AtomicInteger
    val result =
      Source(Seq(true, false))
        .mapAsyncPartitioned(2, 1)(identity)(f)
        .addAttributes(ActorAttributes.supervisionStrategy {
          case x if x == thisWillNotStand =>
            failCount.incrementAndGet()
            Supervision.resume

          case _ => Supervision.stop
        })
        .runWith(Sink.seq)

    result.futureValue should contain only false
    failCount.get() shouldBe 1
  }

  "not invoke the decider twice for the same failed future" in {
    import system.dispatcher

    deciderTest { (elem, _) =>
      Future {
        if (elem) throw thisWillNotStand
        else elem
      }
    }
  }

  "not invoke the decider twice for the same pre-failed future" in {
    deciderTest { (elem, _) =>
      if (elem) Future.failed(thisWillNotStand)
      else Future.successful(elem)
    }
  }

  "not invoke the decider twice for the same failure to produce a future" in {
    deciderTest { (elem, _) =>
      if (elem) throw thisWillNotStand
      else Future.successful(elem)
    }
  }
}
