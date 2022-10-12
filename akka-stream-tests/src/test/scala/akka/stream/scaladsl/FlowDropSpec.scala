/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.ThreadLocalRandom.{ current => random }

import scala.concurrent.duration.DurationInt

import akka.stream.Attributes
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource

class FlowDropSpec extends StreamSpec("""
    akka.stream.materializer.initial-input-buffer-size = 2
  """) with ScriptedTest {

  "A Drop" must {

    "drop" in {
      def script(d: Int) =
        Script(TestConfig.RandomTestRange.map { n =>
          Seq(n) -> (if (n <= d) Nil else Seq(n))
        }: _*)
      TestConfig.RandomTestRange.foreach { _ =>
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d))(_.drop(d))
      }
    }

    "not drop anything for negative n" in {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(List(1, 2, 3)).drop(-1).to(Sink.fromSubscriber(probe)).run()
      probe.expectSubscription().request(10)
      probe.expectNext(1)
      probe.expectNext(2)
      probe.expectNext(3)
      probe.expectComplete()
    }

    "drop elements without demand" in {
      val (inProbe, outProbe) =
        TestSource[Int]().drop(5).toMat(TestSink[Int]())(Keep.both).addAttributes(Attributes.inputBuffer(1, 1)).run()

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
      Source(1 to 1000).drop(1000).runWith(TestSink.apply[Int]()).ensureSubscription().expectComplete()
    }

  }

}
