/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.TimeoutException
import akka.Done
import akka.stream.scaladsl._
import akka.stream.testkit.Utils._
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import akka.testkit.AkkaSpec

class TimeoutsSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "InitialTimeout" must {

    "pass through elements unmodified" in assertAllStagesStopped {
      Await.result(
        Source(1 to 100).initialTimeout(2.seconds).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)
    }

    "pass through error unmodified" in assertAllStagesStopped {
      a[TE] shouldBe thrownBy {
        Await.result(
          Source(1 to 100)
            .concat(Source.failed(TE("test")))
            .initialTimeout(2.seconds)
            .grouped(200)
            .runWith(Sink.head),
          3.seconds)
      }
    }

    "fail if no initial element passes until timeout" in assertAllStagesStopped {
      val downstreamProbe = TestSubscriber.probe[Int]()
      Source.maybe[Int]
        .initialTimeout(1.second)
        .runWith(Sink.fromSubscriber(downstreamProbe))

      downstreamProbe.expectSubscription()
      downstreamProbe.expectNoMsg(500.millis)

      val ex = downstreamProbe.expectError()
      ex.getMessage should ===("The first element has not yet passed through in 1 second.")
    }

  }

  "CompletionTimeout" must {

    "pass through elements unmodified" in assertAllStagesStopped {
      Await.result(
        Source(1 to 100).completionTimeout(2.seconds).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)
    }

    "pass through error unmodified" in assertAllStagesStopped {
      a[TE] shouldBe thrownBy {
        Await.result(
          Source(1 to 100).concat(Source.failed(TE("test")))
            .completionTimeout(2.seconds)
            .grouped(200).runWith(Sink.head),
          3.seconds)
      }
    }

    "fail if not completed until timeout" in assertAllStagesStopped {
      val upstreamProbe = TestPublisher.probe[Int]()
      val downstreamProbe = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstreamProbe)
        .completionTimeout(2.seconds)
        .runWith(Sink.fromSubscriber(downstreamProbe))

      upstreamProbe.sendNext(1)
      downstreamProbe.requestNext(1)
      downstreamProbe.expectNoMsg(500.millis) // No timeout yet

      upstreamProbe.sendNext(2)
      downstreamProbe.requestNext(2)
      downstreamProbe.expectNoMsg(500.millis) // No timeout yet

      val ex = downstreamProbe.expectError()
      ex.getMessage should ===("The stream has not been completed in 2 seconds.")
    }

  }

  "IdleTimeout" must {

    "pass through elements unmodified" in assertAllStagesStopped {
      Await.result(
        Source(1 to 100).idleTimeout(2.seconds).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)
    }

    "pass through error unmodified" in assertAllStagesStopped {
      a[TE] shouldBe thrownBy {
        Await.result(
          Source(1 to 100).concat(Source.failed(TE("test")))
            .idleTimeout(2.seconds)
            .grouped(200).runWith(Sink.head),
          3.seconds)
      }
    }

    "fail if time between elements is too large" in assertAllStagesStopped {
      val upstreamProbe = TestPublisher.probe[Int]()
      val downstreamProbe = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstreamProbe)
        .idleTimeout(1.seconds)
        .runWith(Sink.fromSubscriber(downstreamProbe))

      // Two seconds in overall, but won't timeout until time between elements is large enough
      // (i.e. this works differently from completionTimeout)
      for (_ ← 1 to 4) {
        upstreamProbe.sendNext(1)
        downstreamProbe.requestNext(1)
        downstreamProbe.expectNoMsg(500.millis) // No timeout yet
      }

      val ex = downstreamProbe.expectError()
      ex.getMessage should ===("No elements passed in the last 1 second.")
    }

  }

  "IdleTimeoutBidi" must {

    "not signal error in simple loopback case and pass through elements unmodified" in assertAllStagesStopped {
      val timeoutIdentity = BidiFlow.bidirectionalIdleTimeout[Int, Int](2.seconds).join(Flow[Int])

      Await.result(
        Source(1 to 100).via(timeoutIdentity).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)
    }

    "not signal error if traffic is one-way" in assertAllStagesStopped {
      val upstreamWriter = TestPublisher.probe[Int]()
      val downstreamWriter = TestPublisher.probe[String]()

      val upstream = Flow.fromSinkAndSourceMat(Sink.ignore, Source.fromPublisher(upstreamWriter))(Keep.left)
      val downstream = Flow.fromSinkAndSourceMat(Sink.ignore, Source.fromPublisher(downstreamWriter))(Keep.left)

      val assembly: RunnableGraph[(Future[Done], Future[Done])] = upstream
        .joinMat(BidiFlow.bidirectionalIdleTimeout[Int, String](2.seconds))(Keep.left)
        .joinMat(downstream)(Keep.both)

      val (upFinished, downFinished) = assembly.run()

      upstreamWriter.sendNext(1)
      Thread.sleep(1000)
      upstreamWriter.sendNext(1)
      Thread.sleep(1000)
      upstreamWriter.sendNext(1)
      Thread.sleep(1000)

      upstreamWriter.sendComplete()
      downstreamWriter.sendComplete()

      Await.ready(upFinished, 3.seconds)
      Await.ready(downFinished, 3.seconds)
    }

    "be able to signal timeout once no traffic on either sides" in assertAllStagesStopped {
      val upWrite = TestPublisher.probe[String]()
      val upRead = TestSubscriber.probe[Int]()

      val downWrite = TestPublisher.probe[Int]()
      val downRead = TestSubscriber.probe[String]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val timeoutStage = b.add(BidiFlow.bidirectionalIdleTimeout[String, Int](2.seconds))
        Source.fromPublisher(upWrite) ~> timeoutStage.in1;
        timeoutStage.out1 ~> Sink.fromSubscriber(downRead)
        Sink.fromSubscriber(upRead) <~ timeoutStage.out2;
        timeoutStage.in2 <~ Source.fromPublisher(downWrite)
        ClosedShape
      }).run()

      // Request enough for the whole test
      upRead.request(100)
      downRead.request(100)

      upWrite.sendNext("DATA1")
      downRead.expectNext("DATA1")
      Thread.sleep(1500)

      downWrite.sendNext(1)
      upRead.expectNext(1)
      Thread.sleep(1500)

      upWrite.sendNext("DATA2")
      downRead.expectNext("DATA2")
      Thread.sleep(1000)

      downWrite.sendNext(2)
      upRead.expectNext(2)

      upRead.expectNoMsg(500.millis)
      val error1 = upRead.expectError()
      val error2 = downRead.expectError()

      error1.isInstanceOf[TimeoutException] should be(true)
      error1.getMessage should ===("No elements passed in the last 2 seconds.")
      error2 should ===(error1)

      upWrite.expectCancellation()
      downWrite.expectCancellation()
    }

    "signal error to all outputs" in assertAllStagesStopped {
      val upWrite = TestPublisher.probe[String]()
      val upRead = TestSubscriber.probe[Int]()

      val downWrite = TestPublisher.probe[Int]()
      val downRead = TestSubscriber.probe[String]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val timeoutStage = b.add(BidiFlow.bidirectionalIdleTimeout[String, Int](2.seconds))
        Source.fromPublisher(upWrite) ~> timeoutStage.in1;
        timeoutStage.out1 ~> Sink.fromSubscriber(downRead)
        Sink.fromSubscriber(upRead) <~ timeoutStage.out2;
        timeoutStage.in2 <~ Source.fromPublisher(downWrite)
        ClosedShape
      }).run()

      val te = TE("test")

      upWrite.sendError(te)

      upRead.expectSubscriptionAndError(te)
      downRead.expectSubscriptionAndError(te)
      downWrite.expectCancellation()
    }

  }

}
