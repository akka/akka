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

  def assertSuccess(value: Any, fb: Future[Option[Any]]): Unit =
    Await.result(fb, 1.second) should be(Some(value))

  "An AcknowledgeSink" must {

    "send the elements as result of future" in assertAllStagesStopped {
      val queue = Source(List(1, 2, 3)).runWith(Sink.queue(3))
      assertSuccess(1, queue.pull())
      assertSuccess(2, queue.pull())
      assertSuccess(3, queue.pull())
      queue.pull().pipeTo(testActor)
      expectMsg(None)
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
      val queue = Source(probe).runWith(Sink.queue(3, 100.milli))
      val sub = probe.expectSubscription()
      sub.sendError(ex) // potential race condition

      val future = queue.pull()
      future.onFailure { case e ⇒ e.getClass() should be(classOf[AskTimeoutException]); Unit }
      future.onSuccess { case _ ⇒ fail() }

      Await.ready(future, 1.second)
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
