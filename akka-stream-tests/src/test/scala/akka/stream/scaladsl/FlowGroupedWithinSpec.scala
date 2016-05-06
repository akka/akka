/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, ThrottleMode }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.AkkaSpec

import scala.concurrent.Await

class FlowGroupedWithinSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)

  implicit val materializer = ActorMaterializer()

  "A GroupedWithin" must {

    "group elements within the duration" in assertAllStagesStopped {
      val input = Iterator.from(1)
      val p = TestPublisher.manualProbe[Int]()
      val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
      Source.fromPublisher(p).groupedWithin(1000, 1.second).to(Sink.fromSubscriber(c)).run()
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(100)
      val demand1 = pSub.expectRequest.toInt
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand2 = pSub.expectRequest.toInt
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand3 = pSub.expectRequest.toInt
      c.expectNext((1 to (demand1 + demand2).toInt).toVector)
      (1 to demand3) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNoMsg(300.millis)
      c.expectNext(((demand1 + demand2 + 1).toInt to (demand1 + demand2 + demand3).toInt).toVector)
      c.expectNoMsg(300.millis)
      pSub.expectRequest
      val last = input.next()
      pSub.sendNext(last)
      pSub.sendComplete()
      c.expectNext(List(last))
      c.expectComplete
      c.expectNoMsg(200.millis)
    }

    "deliver bufferd elements onComplete before the timeout" in {
      val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
      Source(1 to 3).groupedWithin(1000, 10.second).to(Sink.fromSubscriber(c)).run()
      val cSub = c.expectSubscription
      cSub.request(100)
      c.expectNext((1 to 3).toList)
      c.expectComplete
      c.expectNoMsg(200.millis)
    }

    "buffer groups until requested from downstream" in {
      val input = Iterator.from(1)
      val p = TestPublisher.manualProbe[Int]()
      val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
      Source.fromPublisher(p).groupedWithin(1000, 1.second).to(Sink.fromSubscriber(c)).run()
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(1)
      val demand1 = pSub.expectRequest.toInt
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNext((1 to demand1).toVector)
      val demand2 = pSub.expectRequest.toInt
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNoMsg(300.millis)
      cSub.request(1)
      c.expectNext(((demand1 + 1) to (demand1 + demand2)).toVector)
      pSub.sendComplete()
      c.expectComplete
      c.expectNoMsg(100.millis)
    }

    "drop empty groups" in {
      val p = TestPublisher.manualProbe[Int]()
      val c = TestSubscriber.manualProbe[immutable.Seq[Int]]()
      Source.fromPublisher(p).groupedWithin(1000, 500.millis).to(Sink.fromSubscriber(c)).run()
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(2)
      pSub.expectRequest
      c.expectNoMsg(600.millis)
      pSub.sendNext(1)
      pSub.sendNext(2)
      c.expectNext(List(1, 2))
      // nothing more requested
      c.expectNoMsg(1100.millis)
      cSub.request(3)
      c.expectNoMsg(600.millis)
      pSub.sendComplete()
      c.expectComplete
      c.expectNoMsg(100.millis)
    }

    "reset time window when max elements reached" in {
      val inputs = Iterator.from(1)
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[immutable.Seq[Int]]()
      Source.fromPublisher(upstream).groupedWithin(3, 2.second).to(Sink.fromSubscriber(downstream)).run()

      downstream.request(2)
      downstream.expectNoMsg(1000.millis)

      (1 to 4) foreach { _ ⇒ upstream.sendNext(inputs.next()) }
      downstream.within(1000.millis) {
        downstream.expectNext((1 to 3).toVector)
      }

      downstream.expectNoMsg(1500.millis)

      downstream.within(1000.millis) {
        downstream.expectNext(List(4))
      }

      upstream.sendComplete()
      downstream.expectComplete
      downstream.expectNoMsg(100.millis)
    }

    "group evenly" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.groupedWithin(3, 10.minutes)))
    }

    "group with rest" in {
      def script = Script((TestConfig.RandomTestRange.map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }
        :+ { val x = random.nextInt(); Seq(x) -> Seq(immutable.Seq(x)) }): _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.groupedWithin(3, 10.minutes)))
    }

    "group with small groups with backpressure" in {
      Source(1 to 10)
        .groupedWithin(1, 1.day)
        .throttle(1, 110.millis, 0, ThrottleMode.Shaping)
        .runWith(Sink.seq).futureValue should ===((1 to 10).map(List(_)))
    }

  }

}
