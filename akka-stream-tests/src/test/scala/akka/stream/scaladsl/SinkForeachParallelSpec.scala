/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.stream.ActorMaterializer
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.testkit.{ TestLatch, TestProbe }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class SinkForeachParallelSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  "A ForeachParallel" must {
    "produce elements in the order they are ready" in assertAllStagesStopped {
      import system.dispatcher

      val probe = TestProbe()
      val latch = (1 to 4).map(_ → TestLatch(1)).toMap
      val p = Source(1 to 4).runWith(Sink.foreachParallel(4)((n: Int) ⇒ {
        Await.ready(latch(n), 5.seconds)
        probe.ref ! n
      }))
      latch(2).countDown()
      probe.expectMsg(2)
      latch(4).countDown()
      probe.expectMsg(4)
      latch(3).countDown()
      probe.expectMsg(3)

      assert(!p.isCompleted)

      latch(1).countDown()
      probe.expectMsg(1)

      Await.result(p, 4.seconds)
      assert(p.isCompleted)
    }

    "not run more functions in parallel then specified" in {
      import system.dispatcher

      val probe = TestProbe()
      val latch = (1 to 5).map(_ → TestLatch()).toMap

      val p = Source(1 to 5).runWith(Sink.foreachParallel(4)((n: Int) ⇒ {
        probe.ref ! n
        Await.ready(latch(n), 5.seconds)
      }))
      probe.expectMsgAllOf(1, 2, 3, 4)
      probe.expectNoMsg(200.millis)

      assert(!p.isCompleted)

      for (i ← 1 to 4) latch(i).countDown()

      latch(5).countDown()
      probe.expectMsg(5)

      Await.result(p, 5.seconds)
      assert(p.isCompleted)

    }

    "resume after function failure" in assertAllStagesStopped {
      import system.dispatcher

      val probe = TestProbe()
      val latch = TestLatch(1)

      val p = Source(1 to 5).runWith(Sink.foreachParallel(4)((n: Int) ⇒ {
        if (n == 3) throw new RuntimeException("err1") with NoStackTrace
        else {
          probe.ref ! n
          Await.ready(latch, 10.seconds)
        }
      }).withAttributes(supervisionStrategy(resumingDecider)))

      latch.countDown()
      probe.expectMsgAllOf(1, 2, 4, 5)

      Await.result(p, 5.seconds)
    }

    "finish after function thrown exception" in assertAllStagesStopped {
      import system.dispatcher

      val probe = TestProbe()
      val element4Latch = new CountDownLatch(1)
      val errorLatch = new CountDownLatch(2)

      val p = Source.fromIterator(() ⇒ Iterator.from(1)).runWith(Sink.foreachParallel(3)((n: Int) ⇒ {
        if (n == 3) {
          // Error will happen only after elements 1, 2 has been processed
          errorLatch.await(5, TimeUnit.SECONDS)
          throw new RuntimeException("err2") with NoStackTrace
        } else {
          probe.ref ! n
          errorLatch.countDown()
          element4Latch.await(5, TimeUnit.SECONDS) // Block element 4, 5, 6, ... from entering
        }
      }).withAttributes(supervisionStrategy(stoppingDecider)))

      // Only the first two messages are guaranteed to arrive due to their enforced ordering related to the time
      // of failure.
      probe.expectMsgAllOf(1, 2)
      element4Latch.countDown() // Release elements 4, 5, 6, ...

      a[RuntimeException] must be thrownBy Await.result(p, 3.seconds)
    }

    "handle empty source" in assertAllStagesStopped {
      import system.dispatcher

      val p = Source(List.empty[Int]).runWith(Sink.foreachParallel(3)(a ⇒ ()))

      Await.result(p, 200.seconds)
    }

  }

}
