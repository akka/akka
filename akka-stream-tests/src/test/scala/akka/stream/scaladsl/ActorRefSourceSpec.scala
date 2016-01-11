/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.stream.testkit.Utils._
import akka.actor.PoisonPill
import akka.actor.Status

class ActorRefSourceSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()

  "A ActorRefSource" must {

    "emit received messages to the stream" in {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(10, OverflowStrategy.fail).to(Sink(s)).run()
      val sub = s.expectSubscription
      sub.request(2)
      ref ! 1
      s.expectNext(1)
      ref ! 2
      s.expectNext(2)
      ref ! 3
      s.expectNoMsg(500.millis)
    }

    "buffer when needed" in {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(100, OverflowStrategy.dropHead).to(Sink(s)).run()
      val sub = s.expectSubscription
      for (n ← 1 to 20) ref ! n
      sub.request(10)
      for (n ← 1 to 10) s.expectNext(n)
      sub.request(10)
      for (n ← 11 to 20) s.expectNext(n)

      for (n ← 200 to 399) ref ! n
      sub.request(100)
      for (n ← 300 to 399) s.expectNext(n)
    }

    "drop new when full and with dropNew strategy" in {
      val (ref, sub) = Source.actorRef(100, OverflowStrategy.dropNew).toMat(TestSink.probe[Int])(Keep.both).run()

      for (n ← 1 to 20) ref ! n
      sub.request(10)
      for (n ← 1 to 10) sub.expectNext(n)
      sub.request(10)
      for (n ← 11 to 20) sub.expectNext(n)

      for (n ← 200 to 399) ref ! n
      sub.request(100)
      for (n ← 200 to 299) sub.expectNext(n)
    }

    "terminate when the stream is cancelled" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(0, OverflowStrategy.fail).to(Sink(s)).run()
      watch(ref)
      val sub = s.expectSubscription
      sub.cancel()
      expectTerminated(ref)
    }

    "not fail when 0 buffer space and demand is signalled" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(0, OverflowStrategy.dropHead).to(Sink(s)).run()
      watch(ref)
      val sub = s.expectSubscription
      sub.request(100)
      sub.cancel()
      expectTerminated(ref)
    }

    "complete the stream immediatly when receiving PoisonPill" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(10, OverflowStrategy.fail).to(Sink(s)).run()
      val sub = s.expectSubscription
      ref ! PoisonPill
      s.expectComplete()
    }

    "signal buffered elements and complete the stream after receiving Status.Success" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(3, OverflowStrategy.fail).to(Sink(s)).run()
      val sub = s.expectSubscription
      ref ! 1
      ref ! 2
      ref ! 3
      ref ! Status.Success("ok")
      sub.request(10)
      s.expectNext(1, 2, 3)
      s.expectComplete()
    }

    "not buffer elements after receiving Status.Success" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(3, OverflowStrategy.dropBuffer).to(Sink(s)).run()
      val sub = s.expectSubscription
      ref ! 1
      ref ! 2
      ref ! 3
      ref ! Status.Success("ok")
      ref ! 100
      ref ! 100
      ref ! 100
      sub.request(10)
      s.expectNext(1, 2, 3)
      s.expectComplete()
    }

    "after receiving Status.Success, allow for earlier completion with PoisonPill" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(3, OverflowStrategy.dropBuffer).to(Sink(s)).run()
      val sub = s.expectSubscription
      ref ! 1
      ref ! 2
      ref ! 3
      ref ! Status.Success("ok")
      sub.request(2) // not all elements drained yet
      s.expectNext(1, 2)
      ref ! PoisonPill
      s.expectComplete() // element `3` not signaled
    }

    "fail the stream when receiving Status.Failure" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(10, OverflowStrategy.fail).to(Sink(s)).run()
      val sub = s.expectSubscription
      val exc = TE("testfailure")
      ref ! Status.Failure(exc)
      s.expectError(exc)
    }
  }
}
