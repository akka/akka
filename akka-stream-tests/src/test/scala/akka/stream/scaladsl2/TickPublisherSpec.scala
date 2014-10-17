/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.duration._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import scala.util.control.NoStackTrace

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TickPublisherSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "A Flow based on tick publisher" must {
    "produce ticks" in {
      val tickGen = Iterator from 1
      val c = StreamTestKit.SubscriberProbe[String]()
      Source(1.second, 500.millis, () ⇒ "tick-" + tickGen.next()).connect(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectNoMsg(600.millis)
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
      Source(1.second, 1.second, () ⇒ "tick-" + tickGen.next()).connect(Sink(c)).run()
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
      val p = Source(1.second, 1.second, () ⇒ "tick-" + tickGen.next()).runWith(Sink.publisher)
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
      Source[String](1.second, 1.second, () ⇒ throw new RuntimeException("tick err") with NoStackTrace).connect(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectError.getMessage should be("tick err")
    }

    // FIXME enable this test again when zip is back
    "be usable with zip for a simple form of rate limiting" ignore {
      //      val c = StreamTestKit.SubscriberProbe[Int]()
      //      val rate = Source(1.second, 1.second, () ⇒ "tick").runWith(Sink.publisher)
      //      Source(1 to 100).zip(rate).map { case (n, _) ⇒ n }.connect(Sink(c)).run()
      //      val sub = c.expectSubscription()
      //      sub.request(1000)
      //      c.expectNext(1)
      //      c.expectNoMsg(200.millis)
      //      c.expectNext(2)
      //      c.expectNoMsg(200.millis)
      //      sub.cancel()
    }

  }
}