/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import akka.stream.ActorFlowMaterializer
import akka.stream.OverflowStrategy
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.actor.PoisonPill
import akka.actor.Status

class ActorRefSourceSpec extends AkkaSpec {
  implicit val mat = ActorFlowMaterializer()

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

    "terminate when the stream is cancelled" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(0, OverflowStrategy.fail).to(Sink(s)).run()
      watch(ref)
      val sub = s.expectSubscription
      sub.cancel()
      expectTerminated(ref)
    }

    "complete the stream when receiving PoisonPill" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(10, OverflowStrategy.fail).to(Sink(s)).run()
      val sub = s.expectSubscription
      ref ! PoisonPill
      s.expectComplete()
    }

    "complete the stream when receiving Status.Success" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val ref = Source.actorRef(10, OverflowStrategy.fail).to(Sink(s)).run()
      val sub = s.expectSubscription
      ref ! Status.Success("ok")
      s.expectComplete()
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
