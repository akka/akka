/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.scaladsl.Flow

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTakeWithinSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer(MaterializerSettings(
    dispatcher = "akka.test.stream-dispatcher"))

  "A TakeWithin" must {

    "deliver elements within the duration, but not afterwards" in {
      val input = Iterator.from(1)
      val p = StreamTestKit.PublisherProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[Int]()
      Flow(p).takeWithin(1.second).produceTo(c)
      val pSub = p.expectSubscription
      val cSub = c.expectSubscription
      cSub.request(100)
      val demand1 = pSub.expectRequest
      (1 to demand1) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand2 = pSub.expectRequest
      (1 to demand2) foreach { _ ⇒ pSub.sendNext(input.next()) }
      val demand3 = pSub.expectRequest
      val sentN = demand1 + demand2
      (1 to sentN) foreach { n ⇒ c.expectNext(n) }
      within(2.seconds) {
        c.expectComplete
      }
      (1 to demand3) foreach { _ ⇒ pSub.sendNext(input.next()) }
      c.expectNoMsg(200.millis)
    }

    "deliver bufferd elements onComplete before the timeout" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      Flow(1 to 3).takeWithin(1.second).produceTo(c)
      val cSub = c.expectSubscription
      c.expectNoMsg(200.millis)
      cSub.request(100)
      (1 to 3) foreach { n ⇒ c.expectNext(n) }
      c.expectComplete
      c.expectNoMsg(200.millis)
    }

  }

}
