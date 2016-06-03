/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils._
import akka.testkit.AkkaSpec
import akka.testkit.{ TestLatch, TestProbe }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class SinkForeachParallelSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A ForeachParallel" must {
    "produce elements in the order they are ready" in assertAllStagesStopped {
      implicit val ec = system.dispatcher

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
      implicit val ec = system.dispatcher

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
      implicit val ec = system.dispatcher

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
      val probe = TestProbe()
      val latch = TestLatch(1)

      implicit val ec = system.dispatcher
      val p = Source(1 to 5).runWith(Sink.foreachParallel(3)((n: Int) ⇒ {
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          probe.ref ! n
          Await.ready(latch, 10.seconds)
        }
      }).withAttributes(supervisionStrategy(stoppingDecider)))
      p.onFailure { case e ⇒ assert(e.getMessage.equals("err2")); Unit }
      p.onSuccess { case _ ⇒ fail() }

      latch.countDown()
      probe.expectMsgAllOf(1, 2)

      Await.ready(p, 1.seconds)

      assert(p.isCompleted)
    }

    "handle empty source" in assertAllStagesStopped {
      implicit val ec = system.dispatcher

      val p = Source(List.empty[Int]).runWith(Sink.foreachParallel(3)(a ⇒ ()))

      Await.result(p, 200.seconds)
    }

  }

}
