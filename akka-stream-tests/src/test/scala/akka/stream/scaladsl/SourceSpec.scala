/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.impl.PublisherSource
import akka.stream.testkit.StreamTestKit.PublisherProbe
import akka.stream.testkit.StreamTestKit.SubscriberProbe

class SourceSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "Singleton Source" must {
    "produce element" in {
      val p = Source.single(1).runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce elements to later subscriber" in {
      val p = Source.single(1).runWith(Sink.publisher())
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      c1.expectNext(1)
      c1.expectComplete()
      p.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      c2.expectNext(1)
      c2.expectComplete()
    }

  }

  "Empty Source" must {
    "complete immediately" in {
      val p = Source.empty.runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectComplete()

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectComplete()
    }
  }

  "Failed Source" must {
    "emit error immediately" in {
      val ex = new RuntimeException with NoStackTrace
      val p = Source.failed(ex).runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectError(ex)

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectError(ex)
    }
  }

  "Composite Source" must {
    "merge from many inputs" in {
      val probes = Seq.fill(5)(PublisherProbe[Int])
      val source = Source.subscriber[Int]
      val out = SubscriberProbe[Int]

      val s = Source(source, source, source, source, source)(Seq(_, _, _, _, _)) { implicit b ⇒
        (i0, i1, i2, i3, i4) ⇒
          import FlowGraph.Implicits._
          val m = b.add(Merge[Int](5))
          i0.outlet ~> m.in(0)
          i1.outlet ~> m.in(1)
          i2.outlet ~> m.in(2)
          i3.outlet ~> m.in(3)
          i4.outlet ~> m.in(4)
          m.out
      }.to(Sink(out)).run()

      for (i ← 0 to 4) probes(i).subscribe(s(i))
      val sub = out.expectSubscription()
      sub.request(10)

      val subs = for (i ← 0 to 4) {
        val s = probes(i).expectSubscription()
        s.expectRequest()
        s.sendNext(i)
        s.sendComplete()
      }

      val gotten = for (_ ← 0 to 4) yield out.expectNext()
      gotten.toSet should ===(Set(0, 1, 2, 3, 4))
      out.expectComplete()
    }
  }

}