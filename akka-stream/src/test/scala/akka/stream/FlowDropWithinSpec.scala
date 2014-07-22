/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowDropWithinSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    dispatcher = "akka.test.stream-dispatcher"))

  "A DropWithin" must {

    "deliver elements after the duration, but not before" in {
      val input = Iterator.from(1)
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[Int]()
      Flow(p).dropWithin(1.second).produceTo(materializer, c)
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(100)
      val demand1 = pSub.expectRequest
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand2 = pSub.expectRequest
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand3 = pSub.expectRequest
      c.expectNoMsg(1500.millis)
      (1 to demand3) foreach { _ ⇒ pSub.sendNext(input.next()) }
      ((demand1 + demand2 + 1) to (demand1 + demand2 + demand3)) foreach { n ⇒ c.expectNext(n) }
      pSub.sendComplete()
      c.expectComplete
      c.expectNoMsg(200.millis)
    }

  }

}