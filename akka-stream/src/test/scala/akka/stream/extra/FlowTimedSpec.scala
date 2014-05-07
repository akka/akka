/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import akka.stream.testkit.{ ScriptedTest, AkkaSpec }
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.testkit.TestProbe

class FlowTimedSpec extends AkkaSpec with ScriptedTest {

  import scala.concurrent.duration._

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16,
    dispatcher = "akka.test.stream-dispatcher")

  lazy val metricsConfig = system.settings.config

  val gen = FlowMaterializer(settings)

  "Timed" must {

    import akka.stream.extra.Implicits.TimedFlowDsl

    "measure time it takes to go through intermediate" in {
      val testActor = TestProbe()

      val measureBetweenEvery = 100
      val printInfo = (interval: Duration) ⇒ {
        testActor.ref ! interval
        info(s"Measured interval between $measureBetweenEvery elements was: $interval")
      }

      val n = 500
      val testRuns = 1 to 2

      def script = Script((1 to n) map { x ⇒ Seq(x) -> Seq(x) }: _*)
      testRuns foreach (_ ⇒ runScript(script, settings) { flow ⇒
        flow.
          map(identity).
          timedIntervalBetween(_ % measureBetweenEvery == 0, onInterval = printInfo)
      })

      val expectedNrOfOnIntervalCalls = testRuns.size * ((n / measureBetweenEvery) - 1) // first time has no value to compare to, so skips calling onInterval
      1 to expectedNrOfOnIntervalCalls foreach { _ ⇒ testActor.expectMsgType[Duration] }
    }

    "measure time it takes from start to complete, by wrapping operations" in {
      val testActor = TestProbe()

      val n = 500
      val printInfo = (d: FiniteDuration) ⇒ {
        testActor.ref ! d
        info(s"Processing $n elements took $d")
      }

      val testRuns = 1 to 3

      def script = Script((1 to n) map { x ⇒ Seq(x) -> Seq(x) }: _*)
      testRuns foreach (_ ⇒ runScript(script, settings) { flow ⇒
        flow.timed(_.map(identity), onComplete = printInfo)
      })

      testRuns foreach { _ ⇒ testActor.expectMsgType[Duration] }
      testActor.expectNoMsg(1.second)
    }

    "have a Java API" in pending
  }

}

