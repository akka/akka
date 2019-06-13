/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.actor.Status
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.ActorMaterializer
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
      val (ref, s) = Source.actorRefWithAck[Int](AckMsg).toMat(TestSink.probe[Int])(Keep.both).run()

      val sub = s.expectSubscription()
      for (n <- 1 to 20) ref ! n
      sub.request(1)

      @scala.annotation.tailrec
      def verifyNext(n: Int): Unit = {
        if (n > 10)
          s.expectComplete()
        else
          s.expectNextOrError() match {
            case Right(`n`) => verifyNext(n + 1)
            case Right(x)   => fail(s"expected $n, got $x")
            case Left(t)    => t.getMessage shouldBe "Received new element before ack was signaled back"
          }
      }
      verifyNext(1)
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
