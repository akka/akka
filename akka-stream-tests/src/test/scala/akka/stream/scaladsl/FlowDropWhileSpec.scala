/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.concurrent.duration.DurationInt

import akka.stream.ActorAttributes._
import akka.stream.Attributes
import akka.stream.Supervision._
import akka.stream.testkit._
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource

class FlowDropWhileSpec extends StreamSpec {

  "A DropWhile" must {

    "drop while predicate is true" in {
      Source(1 to 4).dropWhile(_ < 3).runWith(TestSink[Int]()).request(2).expectNext(3, 4).expectComplete()
    }

    "complete the future for an empty stream" in {
      Source.empty[Int].dropWhile(_ < 2).runWith(TestSink[Int]()).request(1).expectComplete()
    }

    "continue if error" in {
      Source(1 to 4)
        .dropWhile(a => if (a < 3) true else throw TE(""))
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink[Int]())
        .request(1)
        .expectComplete()
    }

    "restart with strategy" in {
      Source(1 to 4)
        .dropWhile {
          case 1 | 3 => true
          case 4     => false
          case 2     => throw TE("")
        }
        .withAttributes(supervisionStrategy(restartingDecider))
        .runWith(TestSink[Int]())
        .request(1)
        .expectNext(4)
        .expectComplete()
    }

    "drop elements without demand" in {
      val (inProbe, outProbe) =
        TestSource[Int]()
          .dropWhile(_ <= 1000)
          .toMat(TestSink[Int]())(Keep.both)
          .addAttributes(Attributes.inputBuffer(1, 1))
          .run()

      outProbe.ensureSubscription()
      // none of those should fail even without demand
      inProbe.sendNext(1).sendNext(2).sendNext(3).sendNext(4).sendNext(5).sendNext(1001).sendNext(1002)

      // now the buffer should be full (1 internal buffer, 1 async buffer at source probe)

      inProbe.expectNoMessage(100.millis).pending shouldBe 0L

      inProbe.sendComplete() // to test later that completion is buffered as well

      outProbe.requestNext(1001)
      outProbe.requestNext(1002)
      outProbe.expectComplete()
    }

    "complete without demand if remaining elements are dropped" in {
      Source(1 to 1000).dropWhile(_ <= 1000).runWith(TestSink.apply[Int]()).ensureSubscription().expectComplete()
    }

  }

}
