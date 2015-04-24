/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import scala.util.control.NoStackTrace
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit._
import akka.stream.impl.PublisherSource
import akka.stream.impl.ReactiveStreamsCompliance

class SourceSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

  "Single Source" must {
    "produce element" in {
      val p = Source.single(1).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "reject later subscriber" in {
      val p = Source.single(1).runWith(Sink.publisher)
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      c1.expectNext(1)
      c1.expectComplete()

      p.subscribe(c2)
      c2.expectSubscriptionAndError()
    }

  }

  "Empty Source" must {
    "complete immediately" in {
      val p = Source.empty.runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      c.expectSubscriptionAndComplete()

      // reject additional subscriber
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c2)
      c2.expectSubscriptionAndError()
    }
  }

  "Failed Source" must {
    "emit error immediately" in {
      val ex = new RuntimeException with NoStackTrace
      val p = Source.failed(ex).runWith(Sink.publisher)
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      c.expectSubscriptionAndError(ex)

      // reject additional subscriber
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c2)
      c2.expectSubscriptionAndError()
    }
  }

  "Lazy Empty Source" must {
    "complete materialized future when stream cancels" in {
      val neverSource = Source.lazyEmpty
      val pubSink = Sink.publisher

      val (f, neverPub) = neverSource.toMat(pubSink)(Keep.both).run()

      val c = TestSubscriber.manualProbe()
      neverPub.subscribe(c)
      val subs = c.expectSubscription()

      subs.request(1000)
      c.expectNoMsg(300.millis)

      subs.cancel()
      Await.result(f.future, 500.millis)
    }

    "allow external triggering of completion" in {
      val neverSource = Source.lazyEmpty[Int]
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.success(())

      val ready = Await.ready(counterFuture, 500.millis)
      val Success(0) = ready.value.get
    }

    "allow external triggering of onError" in {
      val neverSource = Source.lazyEmpty
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.failure(new Exception("Boom") with NoStackTrace)

      val ready = Await.ready(counterFuture, 500.millis)
      val Failure(ex) = ready.value.get
      ex.getMessage should include("Boom")
    }

  }

  "Composite Source" must {
    "merge from many inputs" in {
      val probes = Seq.fill(5)(TestPublisher.manualProbe[Int])
      val source = Source.subscriber[Int]
      val out = TestSubscriber.manualProbe[Int]

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

  "Repeat Source" must {
    "repeat as long as it takes" in {
      import FlowGraph.Implicits._
      val result = Await.result(Source.repeat(42).grouped(10000).runWith(Sink.head), 1.second)
      result.size should ===(10000)
      result.toSet should ===(Set(42))
    }
  }

  "Iterator Source" must {
    "properly iterate" in {
      val result = Await.result(Source(() ⇒ Iterator.iterate(false)(!_)).grouped(10).runWith(Sink.head), 1.second)
      result should ===(Seq(false, true, false, true, false, true, false, true, false, true))
    }
  }

}
