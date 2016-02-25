/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.actor.Status
import akka.pattern.pipe
import akka.stream.Attributes.inputBuffer
import akka.stream.{ ActorMaterializer }
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.testkit.AkkaSpec

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

    "fail pull future when stream is completed" in assertAllStagesStopped {
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(Sink.queue())
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      sub.sendNext(1)
      expectMsg(Some(1))

      sub.sendComplete()
      Await.result(queue.pull(), noMsgTimeout) should be(None)

      queue.pull().onFailure { case e ⇒ e.isInstanceOf[IllegalStateException] should ===(true) }
    }

    "keep on sending even after the buffer has been full" in assertAllStagesStopped {
      val bufferSize = 16
      val streamElementCount = bufferSize + 4
      val sink = Sink.queue[Int]()
        .withAttributes(inputBuffer(bufferSize, bufferSize))
      val (probe, queue) = Source(1 to streamElementCount)
        .alsoToMat(Flow[Int].take(bufferSize).watchTermination()(Keep.right).to(Sink.ignore))(Keep.right)
        .toMat(sink)(Keep.both)
        .run()
      probe.futureValue should ===(akka.Done)
      for (i ← 1 to streamElementCount) {
        queue.pull() pipeTo testActor
        expectMsg(Some(i))
      }
      queue.pull() pipeTo testActor
      expectMsg(None)

    }

    "work with one element buffer" in assertAllStagesStopped {
      val sink = Sink.queue[Int]().withAttributes(inputBuffer(1, 1))
      val probe = TestPublisher.manualProbe[Int]()
      val queue = Source.fromPublisher(probe).runWith(sink)
      val sub = probe.expectSubscription()

      queue.pull().pipeTo(testActor)
      sub.sendNext(1) // should pull next element
      expectMsg(Some(1))

      queue.pull().pipeTo(testActor)
      expectNoMsg() // element requested but buffer empty
      sub.sendNext(2)
      expectMsg(Some(2))

      sub.sendComplete()
      Await.result(queue.pull(), noMsgTimeout) should be(None)
    }

    "fail to materialize with zero sized input buffer" in {
      an[IllegalArgumentException] shouldBe thrownBy {
        Source.single(()).runWith(Sink.queue().withAttributes(inputBuffer(0, 0)))
      }
    }
  }
}
