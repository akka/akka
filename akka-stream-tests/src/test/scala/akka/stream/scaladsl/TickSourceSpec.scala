/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.MaterializerSettings

class TickSourceSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "A Flow based on tick publisher" must {
    "produce ticks" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      Source(1.second, 500.millis, "tick").to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectNoMsg(600.millis)
      c.expectNext("tick")
      c.expectNoMsg(200.millis)
      c.expectNext("tick")
      c.expectNoMsg(200.millis)
      c.expectNext("tick")
      sub.cancel()
      c.expectNoMsg(200.millis)
    }

    "drop ticks when not requested" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      Source(1.second, 1.second, "tick").to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext("tick")
      c.expectNoMsg(200.millis)
      c.expectNext("tick")
      c.expectNoMsg(1400.millis)
      sub.request(2)
      c.expectNext("tick")
      c.expectNoMsg(200.millis)
      c.expectNext("tick")
      sub.cancel()
      c.expectNoMsg(200.millis)
    }

    "reject multiple subscribers, but keep the first" in {
      val p = Source(1.second, 1.second, "tick").runWith(Sink.publisher)
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val c2 = StreamTestKit.SubscriberProbe[String]()
      p.subscribe(c1)
      p.subscribe(c2)
      val sub1 = c1.expectSubscription()
      c2.expectError()
      sub1.request(1)
      c1.expectNext("tick")
      c1.expectNoMsg(200.millis)
      sub1.request(2)
      c1.expectNext("tick")
      sub1.cancel()
    }

    "be usable with zip for a simple form of rate limiting" in {
      val c = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val zip = Zip[Int, String]
        Source(1 to 100) ~> zip.left
        Source(1.second, 1.second, "tick") ~> zip.right
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
      val c = StreamTestKit.SubscriberProbe[String]()
      val tickSource = Source(1.second, 500.millis, "tick")
      val m = tickSource.to(Sink(c)).run()
      val cancellable = m.get(tickSource)
      val sub = c.expectSubscription()
      sub.request(3)
      c.expectNoMsg(600.millis)
      c.expectNext("tick")
      c.expectNoMsg(200.millis)
      c.expectNext("tick")
      c.expectNoMsg(200.millis)
      c.expectNext("tick")
      cancellable.cancel()
      awaitCond(cancellable.isCancelled)
      sub.request(3)
      c.expectComplete()
    }

  }
}
