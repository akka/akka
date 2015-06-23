/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._

import akka.stream.ActorMaterializer
import akka.stream.testkit._

class FlowDropWithinSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A DropWithin" must {

    "deliver elements after the duration, but not before" in {
      val input = Iterator.from(1)
      val p = TestPublisher.manualProbe[Int]()
      val c = TestSubscriber.manualProbe[Int]()
      Source(p).dropWithin(1.second).to(Sink(c)).run()
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(100)
      val demand1 = pSub.expectRequest
      (1 to demand1.toInt) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand2 = pSub.expectRequest
      (1 to demand2.toInt) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand3 = pSub.expectRequest
      c.expectNoMsg(1500.millis)
      (1 to demand3.toInt) foreach { _ ⇒ pSub.sendNext(input.next()) }
      ((demand1 + demand2 + 1).toInt to (demand1 + demand2 + demand3).toInt) foreach { n ⇒ c.expectNext(n) }
      pSub.sendComplete()
      c.expectComplete
      c.expectNoMsg(200.millis)
    }

  }

}