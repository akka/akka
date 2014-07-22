/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import scala.util.control.NoStackTrace

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TickPublisherSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    dispatcher = "akka.test.stream-dispatcher"))

  "A Flow based on tick publisher" must {
    "produce ticks" in {
      val tickGen = Iterator from 1
      val c = StreamTestKit.SubscriberProbe[String]()
      Flow(1.second, () ⇒ "tick-" + tickGen.next()).produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectNext("tick-1")
      c.expectNoMsg(200.millis)
      c.expectNext("tick-2")
      c.expectNoMsg(200.millis)
      c.expectNext("tick-3")
      sub.cancel()
      c.expectNoMsg(200.millis)
    }

    "drop ticks when not requested" in {
      val tickGen = Iterator from 1
      val c = StreamTestKit.SubscriberProbe[String]()
      Flow(1.second, () ⇒ "tick-" + tickGen.next()).produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext("tick-1")
      c.expectNoMsg(200.millis)
      c.expectNext("tick-2")
      c.expectNoMsg(1400.millis)
      sub.request(2)
      c.expectNext("tick-4")
      c.expectNoMsg(200.millis)
      c.expectNext("tick-5")
      sub.cancel()
      c.expectNoMsg(200.millis)
    }

    "produce ticks with multiple subscribers" in {
      val tickGen = Iterator from 1
      val p = Flow(1.second, () ⇒ "tick-" + tickGen.next()).toPublisher(materializer)
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val c2 = StreamTestKit.SubscriberProbe[String]()
      p.subscribe(c1)
      p.subscribe(c2)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext("tick-1")
      c2.expectNext("tick-1")
      c2.expectNoMsg(200.millis)
      c2.expectNext("tick-2")
      c1.expectNoMsg(200.millis)
      sub1.request(2)
      sub2.request(2)
      c1.expectNext("tick-3")
      c2.expectNext("tick-3")
      sub1.cancel()
      sub2.cancel()
    }

    "signal onError when tick closure throws" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      Flow(1.second, () ⇒ throw new RuntimeException("tick err") with NoStackTrace).produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectError.getMessage should be("tick err")
    }

    "be usable with zip for a simple form of rate limiting" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      val rate = Flow(1.second, () ⇒ "tick").toPublisher(materializer)
      Flow(1 to 100).zip(rate).map { case (n, _) ⇒ n }.produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.request(1000)
      c.expectNext(1)
      c.expectNoMsg(200.millis)
      c.expectNext(2)
      c.expectNoMsg(200.millis)
      sub.cancel()
    }

  }
}