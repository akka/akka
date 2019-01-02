/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.{ LinkedBlockingQueue, ThreadLocalRandom }
import java.util.concurrent.atomic.AtomicInteger

import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.{ ActorAttributes, ActorMaterializer, Supervision }
import akka.stream.Supervision.resumingDecider
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ TestLatch, TestProbe }
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.annotation.tailrec
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlowMapAsyncSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  "A Flow with mapAsync" must {

    "produce future elements" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 3).mapAsync(4)(n ⇒ Future(n)).runWith(Sink.fromSubscriber(c))
      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMsg(200.millis)
      sub.request(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "produce future elements in order" in {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 50).mapAsync(4)(n ⇒
        if (n % 3 == 0) Future.successful(n)
        else Future {
          Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10))
          n
        }).to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(1000)
      for (n ← 1 to 50) c.expectNext(n)
      c.expectComplete()
    }

    "not run more futures than requested parallelism" in {
      val probe = TestProbe()
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 20).mapAsync(8)(n ⇒ Future {
        probe.ref ! n
        n
      }).to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      probe.expectNoMsg(500.millis)
      sub.request(1)
      probe.receiveN(9).toSet should be((1 to 9).toSet)
      probe.expectNoMsg(500.millis)
      sub.request(2)
      probe.receiveN(2).toSet should be(Set(10, 11))
      probe.expectNoMsg(500.millis)
      sub.request(10)
      probe.receiveN(9).toSet should be((12 to 20).toSet)
      probe.expectNoMsg(200.millis)

      for (n ← 1 to 13) c.expectNext(n)
      c.expectNoMsg(200.millis)
    }

    "signal future already failed" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(4)(n ⇒
        if (n == 3) Future.failed[Int](new TE("err1"))
        else Future {
          Await.ready(latch, 10.seconds)
          n
        }
      ).to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError().getMessage should be("err1")
      latch.countDown()
    }

    "signal future failure" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(4)(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err1") with NoStackTrace
        else {
          Await.ready(latch, 10.seconds)
          n
        }
      }).to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError().getMessage should be("err1")
      latch.countDown()
    }

    "signal future failure asap" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val done = Source(1 to 5)
        .map { n ⇒
          if (n == 1) n
          else {
            // slow upstream should not block the error
            Await.ready(latch, 10.seconds)
            n
          }
        }
        .mapAsync(4) { n ⇒
          if (n == 1) Future.failed(new RuntimeException("err1") with NoStackTrace)
          else Future.successful(n)
        }.runWith(Sink.ignore)
      intercept[RuntimeException] {
        Await.result(done, remainingOrDefault)
      }.getMessage should be("err1")
      latch.countDown()
    }

    "a failure mid-stream MUST cause a failure ASAP (stopping strategy)" in assertAllStagesStopped {
      import system.dispatcher
      val pa = Promise[String]()
      val pb = Promise[String]()
      val pc = Promise[String]()
      val pd = Promise[String]()
      val pe = Promise[String]()
      val pf = Promise[String]()

      val input = pa :: pb :: pc :: pd :: pe :: pf :: Nil

      val probe = Source.fromIterator(() ⇒ input.iterator)
        .mapAsync(5)(p ⇒ p.future.map(_.toUpperCase))
        .runWith(TestSink.probe)

      import TestSubscriber._
      var gotErrorAlready = false
      val elementOrErrorOk: PartialFunction[SubscriberEvent, Unit] = {
        case OnNext("A") ⇒ () // is fine
        case OnNext("B") ⇒ () // is fine
        case OnError(ex) if ex.getMessage == "Boom at C" && !gotErrorAlready ⇒
          gotErrorAlready = true // fine, error can over-take elements
      }
      probe.request(100)

      val boom = new Exception("Boom at C")

      // placing the future completion signals here is important
      // the ordering is meant to expose a race between the failure at C and subsequent elements
      pa.success("a")
      pb.success("b")
      pc.failure(boom)
      pd.success("d")
      pe.success("e")
      pf.success("f")

      probe.expectNextOrError() match {
        case Left(ex) ⇒ ex.getMessage should ===("Boom at C") // fine, error can over-take elements
        case Right("A") ⇒
          probe.expectNextOrError() match {
            case Left(ex) ⇒ ex.getMessage should ===("Boom at C") // fine, error can over-take elements
            case Right("B") ⇒
              probe.expectNextOrError() match {
                case Left(ex)       ⇒ ex.getMessage should ===("Boom at C") // fine, error can over-take elements
                case Right(element) ⇒ fail(s"Got [$element] yet it caused an exception, should not have happened!")
              }
          }
      }
    }

    "a failure mid-stream must skip element with resume strategy" in assertAllStagesStopped {
      val pa = Promise[String]()
      val pb = Promise[String]()
      val pc = Promise[String]()
      val pd = Promise[String]()
      val pe = Promise[String]()
      val pf = Promise[String]()

      val input = pa :: pb :: pc :: pd :: pe :: pf :: Nil

      val elements = Source.fromIterator(() ⇒ input.iterator)
        .mapAsync(5)(p ⇒ p.future)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.seq)

      // the problematic ordering:
      pa.success("a")
      pb.success("b")
      pd.success("d")
      pe.success("e")
      pf.success("f")
      pc.failure(new Exception("Booom!"))

      elements.futureValue should ===(List("a", "b", /* no c */ "d", "e", "f"))
    }

    "signal error from mapAsync" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(4)(n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError().getMessage should be("err2")
      latch.countDown()
    }

    "resume after future failure" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsync(4)(n ⇒ Future {
          if (n == 3) throw new RuntimeException("err3") with NoStackTrace
          else n
        })
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (n ← List(1, 2, 4, 5)) c.expectNext(n)
      c.expectComplete()
    }

    "resume after already failed future" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsync(4)(n ⇒
          if (n == 3) Future.failed(new TE("err3"))
          else Future.successful(n)
        )
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (n ← List(1, 2, 4, 5)) c.expectNext(n)
      c.expectComplete()
    }

    "resume after multiple failures" in assertAllStagesStopped {
      val futures: List[Future[String]] = List(
        Future.failed(Utils.TE("failure1")),
        Future.failed(Utils.TE("failure2")),
        Future.failed(Utils.TE("failure3")),
        Future.failed(Utils.TE("failure4")),
        Future.failed(Utils.TE("failure5")),
        Future.successful("happy!"))

      Await.result(
        Source(futures)
          .mapAsync(2)(identity).withAttributes(supervisionStrategy(resumingDecider))
          .runWith(Sink.head), 3.seconds) should ===("happy!")
    }

    "finish after future failure" in assertAllStagesStopped {
      import system.dispatcher
      Await.result(Source(1 to 3).mapAsync(1)(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err3b") with NoStackTrace
        else n
      }).withAttributes(supervisionStrategy(resumingDecider))
        .grouped(10)
        .runWith(Sink.head), 1.second) should be(Seq(1, 2))
    }

    "resume when mapAsync throws" in {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsync(4)(n ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (n ← List(1, 2, 4, 5)) c.expectNext(n)
      c.expectComplete()
    }

    "signal NPE when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b")).mapAsync(4)(elem ⇒ Future.successful(null)).to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError().getMessage should be(ReactiveStreamsCompliance.ElementMustNotBeNullMsg)
    }

    "resume when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b", "c"))
        .mapAsync(4)(elem ⇒ if (elem == "b") Future.successful(null) else Future.successful(elem))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink.fromSubscriber(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (elem ← List("a", "c")) c.expectNext(elem)
      c.expectComplete()
    }

    "should handle cancel properly" in assertAllStagesStopped {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(pub).mapAsync(4)(Future.successful).runWith(Sink.fromSubscriber(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

    "not run more futures than configured" in assertAllStagesStopped {
      val parallelism = 8

      val counter = new AtomicInteger
      val queue = new LinkedBlockingQueue[(Promise[Int], Long)]

      val timer = new Thread {
        val delay = 50000 // nanoseconds
        var count = 0
        @tailrec final override def run(): Unit = {
          val cont = try {
            val (promise, enqueued) = queue.take()
            val wakeup = enqueued + delay
            while (System.nanoTime() < wakeup) {}
            counter.decrementAndGet()
            promise.success(count)
            count += 1
            true
          } catch {
            case _: InterruptedException ⇒ false
          }
          if (cont) run()
        }
      }
      timer.start

      def deferred(): Future[Int] = {
        if (counter.incrementAndGet() > parallelism) Future.failed(new Exception("parallelism exceeded"))
        else {
          val p = Promise[Int]
          queue.offer(p → System.nanoTime())
          p.future
        }
      }

      try {
        val N = 10000
        Source(1 to N)
          .mapAsync(parallelism)(i ⇒ deferred())
          .runFold(0)((c, _) ⇒ c + 1)
          .futureValue(Timeout(3.seconds)) should ===(N)
      } finally {
        timer.interrupt()
      }
    }

    "not invoke the decider twice for the same failed future" in {
      import system.dispatcher
      val failCount = new AtomicInteger(0)
      val result = Source(List(true, false))
        .mapAsync(1)(elem ⇒
          Future {
            if (elem) throw TE("this has gone too far")
            else elem
          }
        ).addAttributes(supervisionStrategy {
          case TE("this has gone too far") ⇒
            failCount.incrementAndGet()
            Supervision.resume
          case _ ⇒ Supervision.stop
        })
        .runWith(Sink.seq)

      result.futureValue should ===(Seq(false))
      failCount.get() should ===(1)
    }

    "not invoke the decider twice for the same already failed future" in {
      val failCount = new AtomicInteger(0)
      val result = Source(List(true, false))
        .mapAsync(1)(elem ⇒
          if (elem) Future.failed(TE("this has gone too far"))
          else Future.successful(elem)
        ).addAttributes(supervisionStrategy {
          case TE("this has gone too far") ⇒
            failCount.incrementAndGet()
            Supervision.resume
          case _ ⇒ Supervision.stop
        })
        .runWith(Sink.seq)

      result.futureValue should ===(Seq(false))
      failCount.get() should ===(1)
    }

    "not invoke the decider twice for the same failure to produce a future" in {
      import system.dispatcher
      val failCount = new AtomicInteger(0)
      val result = Source(List(true, false))
        .mapAsync(1)(elem ⇒
          if (elem) throw TE("this has gone too far")
          else Future {
            elem
          }
        ).addAttributes(supervisionStrategy {
          case TE("this has gone too far") ⇒
            failCount.incrementAndGet()
            Supervision.resume
          case _ ⇒ Supervision.stop
        })
        .runWith(Sink.seq)

      result.futureValue should ===(Seq(false))
      failCount.get() should ===(1)
    }

  }

}
