/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.util

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.Utils._
import akka.stream.testkit._

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.testkit.AkkaSpec
import org.scalatest.concurrent.Eventually

class One2OneBidiFlowSpec extends AkkaSpec with Eventually {
  implicit val materializer = ActorMaterializer()

  "A One2OneBidiFlow" must {

    def test(flow: Flow[Int, Int, NotUsed]) =
      Source(List(1, 2, 3)).via(flow).grouped(10).runWith(Sink.head)

    "be fully transparent for valid one-to-one streams" in assertAllStagesStopped {
      val f = One2OneBidiFlow[Int, Int](-1) join Flow[Int].map(_ * 2)
      Await.result(test(f), 1.second) should ===(Seq(2, 4, 6))
    }

    "be fully transparent to errors" in {
      val f = One2OneBidiFlow[Int, Int](-1) join Flow[Int].map(x ⇒ 10 / (x - 2))
      an[ArithmeticException] should be thrownBy Await.result(test(f), 1.second)
    }

    "trigger an `OutputTruncationException` if the wrapped stream completes early" in assertAllStagesStopped {
      val flowInProbe = TestSubscriber.probe[Int]()
      val flowOutProbe = TestPublisher.probe[Int]()

      val testSetup = One2OneBidiFlow[Int, Int](-1) join Flow.fromSinkAndSource(
        Sink.fromSubscriber(flowInProbe),
        Source.fromPublisher(flowOutProbe))

      val upstreamProbe = TestPublisher.probe[Int]()
      val downstreamProbe = TestSubscriber.probe[Int]()

      Source.fromPublisher(upstreamProbe).via(testSetup).runWith(Sink.fromSubscriber(downstreamProbe))

      upstreamProbe.ensureSubscription()
      downstreamProbe.ensureSubscription()
      flowInProbe.ensureSubscription()
      flowOutProbe.ensureSubscription()

      downstreamProbe.request(1)
      flowInProbe.request(1)

      upstreamProbe.sendNext(1)
      flowInProbe.expectNext(1)

      // Request is now in the wrapped flow but no reply has been returned at this point, this is a clear truncation

      flowOutProbe.sendComplete()
      upstreamProbe.expectCancellation()
      flowInProbe.expectError(One2OneBidiFlow.OutputTruncationException)
      downstreamProbe.expectError(One2OneBidiFlow.OutputTruncationException)
    }

    "trigger an `UnexpectedOutputException` if the wrapped stream produces out-of-order elements" in assertAllStagesStopped {
      new Test() {
        inIn.sendNext(1)
        inOut.requestNext() should ===(1)

        outIn.sendNext(2)
        outOut.requestNext() should ===(2)

        outOut.request(1)
        outIn.sendNext(3)
        outOut.expectError(new One2OneBidiFlow.UnexpectedOutputException(3))
      }
    }

    "fully propagate cancellation" in assertAllStagesStopped {
      new Test() {
        inIn.sendNext(1)
        inOut.requestNext() should ===(1)

        outIn.sendNext(2)
        outOut.requestNext() should ===(2)

        outOut.cancel()
        outIn.expectCancellation()

        inOut.cancel()
        inIn.expectCancellation()
      }
    }

    "backpressure the input side if the maximum number of pending output elements has been reached" in assertAllStagesStopped {
      val MAX_PENDING = 24
      val EMIT_ELEMENTS = 8

      val out = TestPublisher.probe[Int]()
      val seen = new AtomicInteger

      Source(1 to 1000)
        .log("", seen.set) // strange syntax to execute side-effects for every element that flows through
        .via(One2OneBidiFlow[Int, Int](MAX_PENDING) join Flow.fromSinkAndSourceMat(Sink.ignore, Source.fromPublisher(out))(Keep.left))
        .runWith(Sink.ignore)

      // wait for pending elements to be filled
      // This test (and the one below) depends on the absence of input buffers
      // between the counting stage (`log(...)`) and the One2OneBidiFlow.
      eventually(timeout(1.second)) {
        seen.get should be(MAX_PENDING)
      }

      // now respond to a few input elements
      (1 to EMIT_ELEMENTS) foreach out.sendNext

      // then make sure that again only as many elements are pulled in
      // as were resolved before so that again only MAX_PENDING elements
      // are pending (= MAX_PENDING + EMIT_ELEMENTS should have been pulled in)
      eventually(timeout(1.second)) {
        seen.get should be(MAX_PENDING + EMIT_ELEMENTS)
      }

      out.sendComplete() // To please assertAllStagesStopped
    }

    "not pull when input is closed before suppressed pull can be acted on" in assertAllStagesStopped {
      val in = TestPublisher.probe[Int]()
      val out = TestSubscriber.probe[Int]()
      val wrappedIn = TestSubscriber.probe[Int]()
      val wrappedOut = TestPublisher.probe[Int]()

      Source.fromPublisher(in).via(
        One2OneBidiFlow(maxPending = 1) join Flow.fromSinkAndSource(
          Sink.fromSubscriber(wrappedIn),
          Source.fromPublisher(wrappedOut))
      ).runWith(Sink.fromSubscriber(out))

      out.request(2)
      wrappedOut.expectRequest()
      wrappedIn.request(2)
      in.expectRequest()
      in.sendNext(1)
      wrappedIn.expectNext(1)
      // now we have reached the maxPending limit
      in.sendComplete()
      wrappedOut.sendNext(1)
      out.expectNext(1)
      wrappedIn.expectComplete()
      wrappedOut.sendComplete()
      out.expectComplete()

    }
  }

  class Test(maxPending: Int = -1) {
    val inIn = TestPublisher.probe[Int]()
    val inOut = TestSubscriber.probe[Int]()
    val outIn = TestPublisher.probe[Int]()
    val outOut = TestSubscriber.probe[Int]()

    Source.fromPublisher(inIn).via(One2OneBidiFlow[Int, Int](maxPending) join Flow.fromSinkAndSourceMat(Sink.fromSubscriber(inOut), Source.fromPublisher(outIn))(Keep.left)).runWith(Sink.fromSubscriber(outOut))
  }
}
