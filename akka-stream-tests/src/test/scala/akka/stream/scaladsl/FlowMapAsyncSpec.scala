/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NoStackTrace
import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.TestLatch
import akka.testkit.TestProbe
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.impl.ReactiveStreamsCompliance
import scala.annotation.tailrec
import scala.concurrent.Promise
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue
import org.scalatest.concurrent.ScalaFutures
import akka.testkit.AkkaSpec

class FlowMapAsyncSpec extends AkkaSpec {

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
      val p = Source(1 to 50).mapAsync(4)(n ⇒ Future {
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
          queue.offer(p -> System.nanoTime())
          p.future
        }
      }

      try {
        val N = 10000
        Source(1 to N)
          .mapAsync(parallelism)(i ⇒ deferred())
          .runFold(0)((c, _) ⇒ c + 1)
          .futureValue(PatienceConfig(3.seconds)) should ===(N)
      } finally {
        timer.interrupt()
      }
    }

  }

}
