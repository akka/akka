/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Status
import akka.pattern.{ AskTimeoutException, pipe }
import akka.stream.ActorMaterializer
import akka.stream.testkit.Utils._
import akka.stream.testkit.{ AkkaSpec, _ }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NoStackTrace

class AcknowledgeSinkSpec extends AkkaSpec {
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val ex = new RuntimeException("ex") with NoStackTrace

  val noMsgTimeout = 300.millis

  "An AcknowledgeSink" must {

    "send the elements as result of future" in assertAllStagesStopped {
      val expected = List(Some(1), Some(2), Some(3), None)
      val queue = Source(expected.flatten).runWith(Sink.queue(3))
      expected foreach { v ⇒
        queue.pull() pipeTo testActor
        expectMsg(v)
      }
    }

    "allow to have only one future waiting for result in each point of time" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source(probe).runWith(Sink.queue(3))
      val sub = probe.expectSubscription()
      val future = queue.pull()
      val future2 = queue.pull()
      an[IllegalStateException] shouldBe thrownBy { Await.result(future2, 300.millis) }

      sub.sendNext(1)
      future.pipeTo(testActor)
      expectMsg(Some(1))

      sub.sendComplete()
    }

    "wait for next element from upstream" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source(probe).runWith(Sink.queue(3))
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      expectNoMsg(noMsgTimeout)

      sub.sendNext(1)
      expectMsg(Some(1))
      sub.sendComplete()
    }

    "fail future on stream failure" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source(probe).runWith(Sink.queue(3))
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      expectNoMsg(noMsgTimeout)

      sub.sendError(ex)
      expectMsg(Status.Failure(ex))
    }

    "fail future when stream failed" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source(probe).runWith(Sink.queue(3, 100.millis))
      val sub = probe.expectSubscription()
      sub.sendError(ex) // potential race condition

      an[AskTimeoutException] shouldBe thrownBy { Await.result(queue.pull(), 1.second) }
    }

    "timeout future when stream cannot provide data" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source(probe).runWith(Sink.queue(3))
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      expectNoMsg(noMsgTimeout)

      sub.sendNext(1)
      expectMsg(Some(1))
      sub.sendComplete()
    }

    "work when buffer is 0" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source(probe).runWith(Sink.queue(0))
      val sub = probe.expectSubscription()
      sub.sendNext(1)

      queue.pull().pipeTo(testActor)
      sub.sendNext(2)
      expectMsg(Some(2))
      sub.sendComplete()
    }

  }
}
