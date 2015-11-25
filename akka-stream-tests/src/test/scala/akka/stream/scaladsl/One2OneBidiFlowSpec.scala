/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalactic.ConversionCheckedTripleEquals
import akka.stream.ActorMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit._

class One2OneBidiFlowSpec extends AkkaSpec with ConversionCheckedTripleEquals {
  implicit val mat = ActorMaterializer()

  "A One2OneBidiFlow" must {

    def test(flow: Flow[Int, Int, Unit]) =
      Source(List(1, 2, 3)).via(flow).grouped(10).runWith(Sink.head)

    "be fully transparent for valid one-to-one streams" in {
      val f = One2OneBidiFlow[Int, Int](-1) join Flow[Int].map(_ * 2)
      Await.result(test(f), 1.second) should ===(Seq(2, 4, 6))
    }

    "be fully transparent to errors" in {
      val f = One2OneBidiFlow[Int, Int](-1) join Flow[Int].map(x ⇒ 10 / (x - 2))
      an[ArithmeticException] should be thrownBy Await.result(test(f), 1.second)
    }

    "trigger an `OutputTruncationException` if the wrapped stream terminates early" in {
      val f = One2OneBidiFlow[Int, Int](-1) join Flow[Int].filter(_ < 3)
      a[One2OneBidiFlow.OutputTruncationException.type] should be thrownBy Await.result(test(f), 1.second)
    }

    "trigger an `UnexpectedOutputException` if the wrapped stream produces out-of-order elements" in new Test() {
      inIn.sendNext(1)
      inOut.requestNext() should ===(1)

      outIn.sendNext(2)
      outOut.requestNext() should ===(2)

      outOut.request(1)
      outIn.sendNext(3)
      outOut.expectError(new One2OneBidiFlow.UnexpectedOutputException(3))
    }

    "fully propagate cancellation" in new Test() {
      inIn.sendNext(1)
      inOut.requestNext() should ===(1)

      outIn.sendNext(2)
      outOut.requestNext() should ===(2)

      outOut.cancel()
      outIn.expectCancellation()

      inOut.cancel()
      inIn.expectCancellation()
    }

    "backpressure the input side if the maximum number of pending output elements has been reached" in {
      val MAX_PENDING = 24

      val out = TestPublisher.probe[Int]()
      val seen = new AtomicInteger

      Source(1 to 1000)
        .log("", seen.set)
        .via(One2OneBidiFlow[Int, Int](MAX_PENDING) join Flow.fromSinkAndSourceMat(Sink.ignore, Source(out))(Keep.left))
        .runWith(Sink.ignore)

      Thread.sleep(50)
      val x = seen.get()
      (1 to 8) foreach out.sendNext
      Thread.sleep(50)
      seen.get should ===(x + 8)
    }
  }

  class Test(maxPending: Int = -1) {
    val inIn = TestPublisher.probe[Int]()
    val inOut = TestSubscriber.probe[Int]()
    val outIn = TestPublisher.probe[Int]()
    val outOut = TestSubscriber.probe[Int]()

    Source(inIn).via(One2OneBidiFlow[Int, Int](maxPending) join Flow.fromSinkAndSourceMat(Sink(inOut), Source(outIn))(Keep.left)).runWith(Sink(outOut))
  }
}
