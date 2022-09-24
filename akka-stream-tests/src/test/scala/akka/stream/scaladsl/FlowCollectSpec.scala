/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.ThreadLocalRandom.{ current => random }

import scala.concurrent.duration.DurationInt

import akka.stream.ActorAttributes._
import akka.stream.Attributes
import akka.stream.Supervision._
import akka.stream.testkit.ScriptedTest
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource

class FlowCollectSpec extends StreamSpec with ScriptedTest {

  "A Collect" must {

    "collect" in {
      def script =
        Script(TestConfig.RandomTestRange.map { _ =>
          val x = random.nextInt(0, 10000)
          Seq(x) -> (if ((x & 1) == 0) Seq((x * x).toString) else Seq.empty[String])
        }: _*)
      TestConfig.RandomTestRange.foreach(_ =>
        runScript(script)(_.collect {
          case x if x % 2 == 0 => (x * x).toString
        }))
    }

    "restart when Collect throws" in {
      val pf: PartialFunction[Int, Int] = { case x: Int => if (x == 2) throw TE("") else x }
      Source(1 to 3)
        .collect(pf)
        .withAttributes(supervisionStrategy(restartingDecider))
        .runWith(TestSink[Int]())
        .request(1)
        .expectNext(1)
        .request(1)
        .expectNext(3)
        .request(1)
        .expectComplete()
    }

    "filter out elements without demand" in {
      val (inProbe, outProbe) =
        TestSource[Int]()
          .collect({ case elem if elem > 1000 => elem })
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

    "complete without demand if remaining elements are filtered out with collect" in {
      Source(1 to 1000)
        .collect({ case elem if elem > 1000 => elem })
        .runWith(TestSink.apply[Int]())
        .ensureSubscription()
        .expectComplete()
    }

  }

}
