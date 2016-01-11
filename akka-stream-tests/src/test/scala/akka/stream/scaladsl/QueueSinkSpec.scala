/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Status
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.testkit.Utils._
import akka.stream.testkit.{ AkkaSpec, _ }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class QueueSinkSpec extends AkkaSpec {
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val ex = new RuntimeException("ex") with NoStackTrace

  val noMsgTimeout = 300.millis

  "An QueueSinkSpec" must {

    "send the elements as result of future" in assertAllStagesStopped {
      val expected = List(Some(1), Some(2), Some(3), None)
      val queue = Source(expected.flatten).runWith(Sink.queue())
      expected foreach { v ⇒
        queue.pull() pipeTo testActor
        expectMsg(v)
      }
    }

    "allow to have only one future waiting for result in each point of time" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(Sink.queue())
      val sub = probe.expectSubscription()
      val future = queue.pull()
      val future2 = queue.pull()
      an[IllegalStateException] shouldBe thrownBy { Await.result(future2, 300.millis) }

      sub.sendNext(1)
      future.pipeTo(testActor)
      expectMsg(Some(1))

      sub.sendComplete()
      queue.pull()
    }

    "wait for next element from upstream" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(Sink.queue())
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      expectNoMsg(noMsgTimeout)

      sub.sendNext(1)
      expectMsg(Some(1))
      sub.sendComplete()
      queue.pull()
    }

    "fail future on stream failure" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(Sink.queue())
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      expectNoMsg(noMsgTimeout)

      sub.sendError(ex)
      expectMsg(Status.Failure(ex))
    }

    "fail future when stream failed" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(Sink.queue())
      val sub = probe.expectSubscription()
      sub.sendError(ex)

      the[Exception] thrownBy { Await.result(queue.pull(), 300.millis) } should be(ex)
    }

    "timeout future when stream cannot provide data" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(Sink.queue())
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      expectNoMsg(noMsgTimeout)

      sub.sendNext(1)
      expectMsg(Some(1))
      sub.sendComplete()
      queue.pull()
    }

  }
}
