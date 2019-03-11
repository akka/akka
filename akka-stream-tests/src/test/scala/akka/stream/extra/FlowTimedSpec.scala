/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.extra

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.scaladsl.Sink
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.testkit.TestProbe
import org.reactivestreams.{ Publisher, Subscriber }

class FlowTimedSpec extends StreamSpec with ScriptedTest {

  import scala.concurrent.duration._

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "Timed Source" must {

    import akka.stream.extra.Implicits.TimedFlowDsl

    "measure time it between elements matching a predicate" in {
      val testActor = TestProbe()

      val measureBetweenEvery = 5
      val printInfo = (interval: Duration) => {
        testActor.ref ! interval
        info(s"Measured interval between $measureBetweenEvery elements was: $interval")
      }

      val n = 20
      val testRuns = 1 to 2

      def script =
        Script((1 to n).map { x =>
          Seq(x) -> Seq(x)
        }: _*)
      testRuns.foreach(_ =>
        runScript(script, settings) { flow =>
          flow.map(identity).timedIntervalBetween(_ % measureBetweenEvery == 0, onInterval = printInfo)
        })

      val expectedNrOfOnIntervalCalls = testRuns.size * ((n / measureBetweenEvery) - 1) // first time has no value to compare to, so skips calling onInterval
      (1 to expectedNrOfOnIntervalCalls).foreach { _ =>
        testActor.expectMsgType[Duration]
      }
    }

    "measure time it takes from start to complete, by wrapping operations" in {
      val testActor = TestProbe()

      val n = 50
      val printInfo = (d: FiniteDuration) => {
        testActor.ref ! d
        info(s"Processing $n elements took $d")
      }

      val testRuns = 1 to 3

      def script =
        Script((1 to n).map { x =>
          Seq(x) -> Seq(x)
        }: _*)
      testRuns.foreach(_ =>
        runScript(script, settings) { flow =>
          flow.timed(_.map(identity), onComplete = printInfo)
        })

      testRuns.foreach { _ =>
        testActor.expectMsgType[Duration]
      }
      testActor.expectNoMsg(1.second)
    }

    "have a Java API" in pending
  }

  "Timed Flow" must {
    import akka.stream.extra.Implicits.TimedFlowDsl

    "measure time it between elements matching a predicate" in assertAllStagesStopped {
      val probe = TestProbe()

      val flow: Flow[Int, Long, _] = Flow[Int].map(_.toLong).timedIntervalBetween(in => in % 2 == 1, d => probe.ref ! d)

      val c1 = TestSubscriber.manualProbe[Long]()
      Source(List(1, 2, 3)).via(flow).runWith(Sink.fromSubscriber(c1))

      val s = c1.expectSubscription()
      s.request(100)
      c1.expectNext(1L)
      c1.expectNext(2L)
      c1.expectNext(3L)
      c1.expectComplete()

      val duration = probe.expectMsgType[Duration]
      info(s"Got duration (first): $duration")
    }

    "measure time from start to complete, by wrapping operations" in assertAllStagesStopped {
      val probe = TestProbe()

      // making sure the types come out as expected
      val flow: Flow[Int, String, _] =
        Flow[Int].timed(_.map(_.toDouble).map(_.toInt).map(_.toString), duration => probe.ref ! duration).map {
          s: String =>
            s + "!"
        }

      val (flowIn: Subscriber[Int], flowOut: Publisher[String]) =
        flow.runWith(Source.asSubscriber[Int], Sink.asPublisher[String](false))

      val c1 = TestSubscriber.manualProbe[String]()
      val c2 = flowOut.subscribe(c1)

      val p = Source(0 to 100).runWith(Sink.asPublisher(false))
      p.subscribe(flowIn)

      val s = c1.expectSubscription()
      s.request(200)
      (0 to 100).foreach { i =>
        c1.expectNext(i.toString + "!")
      }
      c1.expectComplete()

      val duration = probe.expectMsgType[Duration]
      info(s"Took: $duration")
    }
  }

}
