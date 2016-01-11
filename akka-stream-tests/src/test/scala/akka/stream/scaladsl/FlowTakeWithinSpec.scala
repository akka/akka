/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._

import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.assertAllStagesStopped

class FlowTakeWithinSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

  "A TakeWithin" must {

    "deliver elements within the duration, but not afterwards" in {
      val input = Iterator.from(1)
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[Int]()
      Source(p).takeWithin(1.second).to(Sink(c)).run()
      val pSub = p.expectSubscription()
      val cSub = c.expectSubscription()
      cSub.request(100)
      val demand1 = pSub.expectRequest().toInt
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand2 = pSub.expectRequest().toInt
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand3 = pSub.expectRequest().toInt
      val sentN = demand1 + demand2
      (1 to sentN) foreach { n ⇒ c.expectNext(n) }
      within(2.seconds) {
        c.expectComplete()
      }
      (1 to demand3) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNoMsg(200.millis)
    }

    "deliver bufferd elements onComplete before the timeout" in assertAllStagesStopped {
      val c = StreamTestKit.SubscriberProbe[Int]()
      Source(1 to 3).takeWithin(1.second).to(Sink(c)).run()
      val cSub = c.expectSubscription()
      c.expectNoMsg(200.millis)
      cSub.request(100)
      (1 to 3) foreach { n ⇒ c.expectNext(n) }
      c.expectComplete()
      c.expectNoMsg(200.millis)
    }

  }

}
