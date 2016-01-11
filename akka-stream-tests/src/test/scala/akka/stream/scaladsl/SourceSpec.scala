/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import scala.util.control.NoStackTrace
import akka.stream.{ SourceShape, ActorMaterializer }
import akka.stream.testkit._
import akka.stream.impl.{ PublisherSource, ReactiveStreamsCompliance }

class SourceSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "Single Source" must {
    "produce element" in {
      val p = Source.single(1).runWith(Sink.publisher(false))
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "reject later subscriber" in {
      val p = Source.single(1).runWith(Sink.publisher(false))
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
      val p = Source.empty.runWith(Sink.publisher(false))
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
      val p = Source.failed(ex).runWith(Sink.publisher(false))
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      c.expectSubscriptionAndError(ex)

      // reject additional subscriber
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c2)
      c2.expectSubscriptionAndError()
    }
  }

  "Maybe Source" must {
    "complete materialized future with None when stream cancels" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val pubSink = Sink.publisher[Int](false)

      val (f, neverPub) = neverSource.toMat(pubSink)(Keep.both).run()

      val c = TestSubscriber.manualProbe[Int]()
      neverPub.subscribe(c)
      val subs = c.expectSubscription()

      subs.request(1000)
      c.expectNoMsg(300.millis)

      subs.cancel()
      Await.result(f.future, 500.millis) shouldEqual None
    }

    "allow external triggering of empty completion" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int].filter(_ ⇒ false)
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.trySuccess(None) shouldEqual true

      Await.result(counterFuture, 500.millis) shouldEqual 0
    }

    "allow external triggering of non-empty completion" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val counterSink = Sink.head[Int]

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.trySuccess(Some(6)) shouldEqual true

      Await.result(counterFuture, 500.millis) shouldEqual 6
    }

    "allow external triggering of onError" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
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
      val probes = Seq.fill(5)(TestPublisher.manualProbe[Int]())
      val source = Source.subscriber[Int]
      val out = TestSubscriber.manualProbe[Int]

      val s = Source.fromGraph(GraphDSL.create(source, source, source, source, source)(Seq(_, _, _, _, _)) { implicit b ⇒
        (i0, i1, i2, i3, i4) ⇒
          import GraphDSL.Implicits._
          val m = b.add(Merge[Int](5))
          i0.outlet ~> m.in(0)
          i1.outlet ~> m.in(1)
          i2.outlet ~> m.in(2)
          i3.outlet ~> m.in(3)
          i4.outlet ~> m.in(4)
          SourceShape(m.out)
      }).to(Sink(out)).run()

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

    "combine from many inputs with simplified API" in {
      val probes = Seq.fill(3)(TestPublisher.manualProbe[Int]())
      val source = for (i ← 0 to 2) yield Source(probes(i))
      val out = TestSubscriber.manualProbe[Int]

      Source.combine(source(0), source(1), source(2))(Merge(_)).to(Sink(out)).run()

      val sub = out.expectSubscription()
      sub.request(3)

      for (i ← 0 to 2) {
        val s = probes(i).expectSubscription()
        s.expectRequest()
        s.sendNext(i)
        s.sendComplete()
      }

      val gotten = for (_ ← 0 to 2) yield out.expectNext()
      gotten.toSet should ===(Set(0, 1, 2))
      out.expectComplete()
    }

    "combine from two inputs with simplified API" in {
      val probes = Seq.fill(2)(TestPublisher.manualProbe[Int]())
      val source = Source(probes(0)) :: Source(probes(1)) :: Nil
      val out = TestSubscriber.manualProbe[Int]

      Source.combine(source(0), source(1))(Merge(_)).to(Sink(out)).run()

      val sub = out.expectSubscription()
      sub.request(3)

      for (i ← 0 to 1) {
        val s = probes(i).expectSubscription()
        s.expectRequest()
        s.sendNext(i)
        s.sendComplete()
      }

      val gotten = for (_ ← 0 to 1) yield out.expectNext()
      gotten.toSet should ===(Set(0, 1))
      out.expectComplete()
    }

  }

  "Repeat Source" must {
    "repeat as long as it takes" in {
      import GraphDSL.Implicits._
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
