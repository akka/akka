/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.actor.Status
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, Attributes }
import akka.stream.testkit.StreamSpec
import akka.testkit.TestProbe

import scala.concurrent.duration._

private object ActorRefBackpressureSourceSpec {
  case object AckMsg
}

class ActorRefBackpressureSourceSpec extends StreamSpec {
  import ActorRefBackpressureSourceSpec._

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  "An Source.actorRefWithAck" must {

    "emit received messages to the stream and ack" in assertAllStagesStopped {
      val probe = TestProbe()
      val (ref, s) = Source.actorRefWithAck[Int](AckMsg).toMat(TestSink.probe[Int])(Keep.both).run()

      val sub = s.expectSubscription()
      sub.request(10)
      probe.send(ref, 1)
      s.expectNext(1)
      probe.expectMsg(AckMsg)

      probe.send(ref, 2)
      s.expectNext(2)
      probe.expectMsg(AckMsg)

      s.expectNoMessage(50.millis)

      ref ! Status.Success("ok")
      s.expectComplete()
    }

    "fail when consumer does not await ack" in assertAllStagesStopped {
      val (ref, s) = Source
        .actorRefWithAck[Int](AckMsg)
        .toMat(TestSink.probe[Int].addAttributes(Attributes.inputBuffer(initial = 1, max = 1)))(Keep.both)
        .run()

      val sub = s.expectSubscription()
      for (n <- 1 to 20) ref ! n
      sub.request(1)

      var e: Either[Throwable, Int] = null
      do {
        e = s.expectNextOrError()
        if (e.right.exists(_ > 10)) fail("Must not drain all remaining elements: " + e)
      } while (e.isRight)
      e.left.get.getMessage shouldBe "Received new element before ack was signaled back"
    }

    "complete after receiving Status.Success" in assertAllStagesStopped {
      val probe = TestProbe()
      val (ref, s) = Source.actorRefWithAck[Int](AckMsg).toMat(TestSink.probe[Int])(Keep.both).run()

      val sub = s.expectSubscription()
      sub.request(10)
      probe.send(ref, 1)
      s.expectNext(1)
      probe.expectMsg(AckMsg)

      ref ! Status.Success("ok")

      s.expectComplete()
    }

    "fail after receiving Status.Failure" in assertAllStagesStopped {
      val probe = TestProbe()
      val (ref, s) = Source.actorRefWithAck[Int](AckMsg).toMat(TestSink.probe[Int])(Keep.both).run()

      val sub = s.expectSubscription()
      sub.request(10)
      probe.send(ref, 1)
      s.expectNext(1)
      probe.expectMsg(AckMsg)

      ref ! Status.Failure(TE("test"))

      s.expectError(TE("test"))
    }

    "not buffer elements after receiving Status.Success" in assertAllStagesStopped {
      val probe = TestProbe()
      val (ref, s) = Source.actorRefWithAck[Int](AckMsg).toMat(TestSink.probe[Int])(Keep.both).run()

      val sub = s.expectSubscription()
      sub.request(10)
      probe.send(ref, 1)
      s.expectNext(1)
      probe.expectMsg(AckMsg)

      probe.send(ref, 2)
      s.expectNext(2)
      probe.expectMsg(AckMsg)

      ref ! Status.Success("ok")

      probe.send(ref, 100)
      probe.send(ref, 100)
      probe.send(ref, 100)
      probe.send(ref, 100)
      probe.expectNoMessage(200.millis)

      s.expectComplete()
    }
  }
}
