/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.scaladsl.Flow
import akka.stream.testkit.ScriptedTest

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowGroupedWithinSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  val materializer = FlowMaterializer(settings)

  "A GroupedWithin" must {

    "group elements within the duration" in {
      val input = Iterator.from(1)
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[immutable.Seq[Int]]()
      Flow(p).groupedWithin(1000, 1.second).produceTo(materializer, c)
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(100)
      val demand1 = pSub.expectRequest
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand2 = pSub.expectRequest
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand3 = pSub.expectRequest
      c.expectNext((1 to (demand1 + demand2)).toVector)
      (1 to demand3) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNoMsg(300.millis)
      c.expectNext(((demand1 + demand2 + 1) to (demand1 + demand2 + demand3)).toVector)
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
      val c = StreamTestKit.SubscriberProbe[immutable.Seq[Int]]()
      Flow(1 to 3).groupedWithin(1000, 10.second).produceTo(materializer, c)
      val cSub = c.expectSubscription
      cSub.request(100)
      c.expectNext((1 to 3).toList)
      c.expectComplete
      c.expectNoMsg(200.millis)
    }

    "buffer groups until requested from downstream" in {
      val input = Iterator.from(1)
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[immutable.Seq[Int]]()
      Flow(p).groupedWithin(1000, 1.second).produceTo(materializer, c)
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(1)
      val demand1 = pSub.expectRequest
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNext((1 to demand1).toVector)
      val demand2 = pSub.expectRequest
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNoMsg(300.millis)
      cSub.request(1)
      c.expectNext(((demand1 + 1) to (demand1 + demand2)).toVector)
      pSub.sendComplete()
      c.expectComplete
      c.expectNoMsg(100.millis)
    }

    "drop empty groups" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[immutable.Seq[Int]]()
      Flow(p).groupedWithin(1000, 500.millis).produceTo(materializer, c)
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
      val input = Iterator.from(1)
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[immutable.Seq[Int]]()
      Flow(p).groupedWithin(3, 2.second).produceTo(materializer, c)
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(4)
      val demand1 = pSub.expectRequest
      demand1 should be(4)
      c.expectNoMsg(1000.millis)
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.probe.within(1000.millis) {
        c.expectNext((1 to 3).toVector)
      }
      c.expectNoMsg(1500.millis)
      c.probe.within(1000.millis) {
        c.expectNext(List(4))
      }
      pSub.sendComplete()
      c.expectComplete
      c.expectNoMsg(100.millis)
    }

    "group evenly" in {
      def script = Script((1 to 20) map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }: _*)
      (1 to 30) foreach (_ ⇒ runScript(script, settings)(_.groupedWithin(3, 10.minutes)))
    }

    "group with rest" in {
      def script = Script(((1 to 20).map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }
        :+ { val x = random.nextInt(); Seq(x) -> Seq(immutable.Seq(x)) }): _*)
      (1 to 30) foreach (_ ⇒ runScript(script, settings)(_.groupedWithin(3, 10.minutes)))
    }

  }

}
