/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

class TickSourceSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "A Flow based on tick publisher" must {
    "produce ticks" in {
      val tickGen = Iterator from 1
      val c = StreamTestKit.SubscriberProbe[String]()
      Source(1.second, 500.millis, () ⇒ "tick-" + tickGen.next()).to(Sink(c)).run()
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
      Source(1.second, 1.second, () ⇒ "tick-" + tickGen.next()).to(Sink(c)).run()
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
      val tickSource = Source[String](1.second, 1.second, () ⇒ throw new RuntimeException("tick err") with NoStackTrace)
      val m = tickSource.to(Sink(c)).run()
      val cancellable = m.get(tickSource)
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectError.getMessage should be("tick err")
      awaitCond(cancellable.isCancelled)
      c.expectNoMsg(100.millis)
    }

    "be usable with zip for a simple form of rate limiting" in {
      val c = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val zip = Zip[Int, String]
        Source(1 to 100) ~> zip.left
        Source(1.second, 1.second, () ⇒ "tick") ~> zip.right
        zip.out ~> Flow[(Int, String)].map { case (n, _) ⇒ n } ~> Sink(c)
      }.run()

      val sub = c.expectSubscription()
      sub.request(1000)
      c.expectNext(1)
      c.expectNoMsg(200.millis)
      c.expectNext(2)
      c.expectNoMsg(200.millis)
      sub.cancel()
    }

    "be possible to cancel" in {
      val tickGen = Iterator from 1
      val c = StreamTestKit.SubscriberProbe[String]()
      val tickSource = Source(1.second, 500.millis, () ⇒ "tick-" + tickGen.next())
      val m = tickSource.to(Sink(c)).run()
      val cancellable = m.get(tickSource)
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectNoMsg(600.millis)
      c.expectNext("tick-1")
      c.expectNoMsg(200.millis)
      c.expectNext("tick-2")
      c.expectNoMsg(200.millis)
      c.expectNext("tick-3")
      cancellable.cancel()
      awaitCond(cancellable.isCancelled)
      sub.request(3)
      c.expectComplete()
    }

  }
}
