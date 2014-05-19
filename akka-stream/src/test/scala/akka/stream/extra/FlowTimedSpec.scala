/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.extra

import akka.stream.testkit.{ AkkaConsumerProbe, StreamTestKit, ScriptedTest, AkkaSpec }
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.testkit.TestProbe
import akka.stream.scaladsl.{ Flow, Duct }
import org.reactivestreams.api.{ Producer, Consumer }

class FlowTimedSpec extends AkkaSpec with ScriptedTest {

  import scala.concurrent.duration._

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16,
    dispatcher = "akka.test.stream-dispatcher")

  lazy val metricsConfig = system.settings.config

  val materializer = FlowMaterializer(settings)

  "Timed Flow" must {

    import akka.stream.extra.Implicits.TimedFlowDsl

    "measure time it between elements matching a predicate" in {
      val testActor = TestProbe()

      val measureBetweenEvery = 50
      val printInfo = (interval: Duration) ⇒ {
        testActor.ref ! interval
        info(s"Measured interval between $measureBetweenEvery elements was: $interval")
      }

      val n = 200
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

  "Timed Duct" must {
    import akka.stream.extra.Implicits.TimedDuctDsl

    "measure time it between elements matching a predicate" in {
      val probe = TestProbe()

      val duct: Duct[Int, Long] = Duct[Int].map(_.toLong).timedIntervalBetween(in ⇒ in % 2 == 1, d ⇒ probe.ref ! d)

      val c1 = StreamTestKit.consumerProbe[Long]()
      val c2: Consumer[Int] = duct.produceTo(materializer, c1)

      val p = Flow(List(1, 2, 3)).toProducer(materializer)
      p.produceTo(c2)

      val s = c1.expectSubscription()
      s.requestMore(100)
      c1.expectNext(1L)
      c1.expectNext(2L)
      c1.expectNext(3L)
      c1.expectComplete()

      val duration = probe.expectMsgType[Duration]
      info(s"Got duration (first): $duration")
    }

    "measure time from start to complete, by wrapping operations" in {
      val probe = TestProbe()

      // making sure the types come out as expected
      val duct: Duct[Int, String] =
        Duct[Int].
          timed(_.
            map(_.toDouble).
            map(_.toInt).
            map(_.toString), duration ⇒ probe.ref ! duration).
          map { s: String ⇒ s + "!" }

      val (ductIn: Consumer[Int], ductOut: Producer[String]) = duct.build(materializer)

      val c1 = StreamTestKit.consumerProbe[String]()
      val c2 = ductOut.produceTo(c1)

      val p = Flow(0 to 100).toProducer(materializer)
      p.produceTo(ductIn)

      val s = c1.expectSubscription()
      s.requestMore(200)
      0 to 100 foreach { i ⇒ c1.expectNext(i.toString + "!") }
      c1.expectComplete()

      val duration = probe.expectMsgType[Duration]
      info(s"Took: $duration")
    }
  }

}

