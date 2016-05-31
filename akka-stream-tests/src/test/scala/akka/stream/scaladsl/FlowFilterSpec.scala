/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.testkit.AkkaSpec

import scala.util.control.NoStackTrace

class FlowFilterSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Filter" must {

    "filter" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x = random.nextInt(); Seq(x) → (if ((x & 1) == 0) Seq(x) else Seq()) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.filter(_ % 2 == 0)))
    }

    "not blow up with high request counts" in {
      val settings = ActorMaterializerSettings(system)
        .withInputBuffer(initialSize = 1, maxSize = 1)
      implicit val materializer = ActorMaterializer(settings)

      val probe = TestSubscriber.manualProbe[Int]()
      Source(List.fill(1000)(0) ::: List(1)).filter(_ != 0).runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.request(Int.MaxValue)
      }

      probe.expectNext(1)
      probe.expectComplete()
    }

    "continue if error" in assertAllStagesStopped {
      val TE = new Exception("TEST") with NoStackTrace {
        override def toString = "TE"
      }

      Source(1 to 3).filter((x: Int) ⇒ if (x == 2) throw TE else true).withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(1, 3)
        .expectComplete()
    }

  }

  "A FilterNot" must {
    "filter based on inverted predicate" in {
      def script = Script(TestConfig.RandomTestRange map
        { _ ⇒
          val x = random.nextInt()
          Seq(x) → (if ((x & 1) == 1) Seq(x) else Seq())
        }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.filterNot(_ % 2 == 0)))
    }
  }

}
