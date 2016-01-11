/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Cancellable

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.ActorFlowMaterializerSettings

class TickSourceSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

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
      c2.expectSubscriptionAndError()
      sub1.request(1)
      c1.expectNext("tick")
      c1.expectNoMsg(200.millis)
      sub1.request(2)
      c1.expectNext("tick")
      sub1.cancel()
    }

    "be usable with zip for a simple form of rate limiting" in {
      val c = StreamTestKit.SubscriberProbe[Int]()

      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        val zip = b.add(Zip[Int, String]())
        Source(1 to 100) ~> zip.in0
        Source(1.second, 1.second, "tick") ~> zip.in1
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
      val cancellable = tickSource.to(Sink(c)).run()
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
