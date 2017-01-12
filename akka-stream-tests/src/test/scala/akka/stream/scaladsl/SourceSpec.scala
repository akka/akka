/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.testkit.DefaultTimeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Span, Millis }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.control.NoStackTrace
import akka.stream._
import akka.stream.testkit._
import akka.NotUsed
import akka.testkit.EventFilter
import scala.collection.immutable

class SourceSpec extends StreamSpec with DefaultTimeout {

  implicit val materializer = ActorMaterializer()
  implicit val config = PatienceConfig(timeout = Span(timeout.duration.toMillis, Millis))

  "Single Source" must {
    "produce element" in {
      val p = Source.single(1).runWith(Sink.asPublisher(false))
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "reject later subscriber" in {
      val p = Source.single(1).runWith(Sink.asPublisher(false))
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
      val p = Source.empty.runWith(Sink.asPublisher(false))
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
      val p = Source.failed(ex).runWith(Sink.asPublisher(false))
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
      val pubSink = Sink.asPublisher[Int](false)

      val (f, neverPub) = neverSource.toMat(pubSink)(Keep.both).run()

      val c = TestSubscriber.manualProbe[Int]()
      neverPub.subscribe(c)
      val subs = c.expectSubscription()

      subs.request(1000)
      c.expectNoMsg(300.millis)

      subs.cancel()
      Await.result(f.future, 3.seconds) shouldEqual None
    }

    "allow external triggering of empty completion" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int].filter(_ ⇒ false)
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.trySuccess(None) shouldEqual true

      Await.result(counterFuture, 3.seconds) shouldEqual 0
    }

    "allow external triggering of non-empty completion" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val counterSink = Sink.head[Int]

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.trySuccess(Some(6)) shouldEqual true

      Await.result(counterFuture, 3.seconds) shouldEqual 6
    }

    "allow external triggering of onError" in Utils.assertAllStagesStopped {
      val neverSource = Source.maybe[Int]
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val (neverPromise, counterFuture) = neverSource.toMat(counterSink)(Keep.both).run()

      // external cancellation
      neverPromise.failure(new Exception("Boom") with NoStackTrace)

      val ready = Await.ready(counterFuture, 3.seconds)
      val Failure(ex) = ready.value.get
      ex.getMessage should include("Boom")
    }

  }

  "Composite Source" must {
    "merge from many inputs" in {
      val probes = immutable.Seq.fill(5)(TestPublisher.manualProbe[Int]())
      val source = Source.asSubscriber[Int]
      val out = TestSubscriber.manualProbe[Int]

      val s = Source.fromGraph(GraphDSL.create(source, source, source, source, source)(immutable.Seq(_, _, _, _, _)) { implicit b ⇒ (i0, i1, i2, i3, i4) ⇒
        import GraphDSL.Implicits._
        val m = b.add(Merge[Int](5))
        i0.out ~> m.in(0)
        i1.out ~> m.in(1)
        i2.out ~> m.in(2)
        i3.out ~> m.in(3)
        i4.out ~> m.in(4)
        SourceShape(m.out)
      }).to(Sink.fromSubscriber(out)).run()

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
      val probes = immutable.Seq.fill(3)(TestPublisher.manualProbe[Int]())
      val source = for (i ← 0 to 2) yield Source.fromPublisher(probes(i))
      val out = TestSubscriber.manualProbe[Int]

      Source.combine(source(0), source(1), source(2))(Merge(_)).to(Sink.fromSubscriber(out)).run()

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
      val probes = immutable.Seq.fill(2)(TestPublisher.manualProbe[Int]())
      val source = Source.fromPublisher(probes(0)) :: Source.fromPublisher(probes(1)) :: Nil
      val out = TestSubscriber.manualProbe[Int]

      Source.combine(source(0), source(1))(Merge(_)).to(Sink.fromSubscriber(out)).run()

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
      val f = Source.repeat(42).grouped(1000).runWith(Sink.head)
      f.futureValue.size should ===(1000)
      f.futureValue.toSet should ===(Set(42))
    }
  }

  "Unfold Source" must {
    val expected = List(9227465, 5702887, 3524578, 2178309, 1346269, 832040, 514229, 317811, 196418, 121393, 75025, 46368, 28657, 17711, 10946, 6765, 4181, 2584, 1597, 987, 610, 377, 233, 144, 89, 55, 34, 21, 13, 8, 5, 3, 2, 1, 1, 0)

    "generate a finite fibonacci sequence" in {
      Source.unfold((0, 1)) {
        case (a, _) if a > 10000000 ⇒ None
        case (a, b)                 ⇒ Some((b, a + b) → a)
      }.runFold(List.empty[Int]) { case (xs, x) ⇒ x :: xs }
        .futureValue should ===(expected)
    }

    "terminate with a failure if there is an exception thrown" in {
      val t = new RuntimeException("expected")
      EventFilter[RuntimeException](message = "expected", occurrences = 1) intercept
        whenReady(
          Source.unfold((0, 1)) {
          case (a, _) if a > 10000000 ⇒ throw t
          case (a, b)                 ⇒ Some((b, a + b) → a)
        }.runFold(List.empty[Int]) { case (xs, x) ⇒ x :: xs }.failed) {
          _ should be theSameInstanceAs (t)
        }
    }

    "generate a finite fibonacci sequence asynchronously" in {
      Source.unfoldAsync((0, 1)) {
        case (a, _) if a > 10000000 ⇒ Future.successful(None)
        case (a, b)                 ⇒ Future(Some((b, a + b) → a))(system.dispatcher)
      }.runFold(List.empty[Int]) { case (xs, x) ⇒ x :: xs }
        .futureValue should ===(expected)
    }

    "generate an unbounded fibonacci sequence" in {
      Source.unfold((0, 1))({ case (a, b) ⇒ Some((b, a + b) → a) })
        .take(36)
        .runFold(List.empty[Int]) { case (xs, x) ⇒ x :: xs }
        .futureValue should ===(expected)
    }
  }

  "Iterator Source" must {
    "properly iterate" in {
      Source.fromIterator(() ⇒ Iterator.iterate(false)(!_))
        .grouped(10)
        .runWith(Sink.head)
        .futureValue should ===(immutable.Seq(false, true, false, true, false, true, false, true, false, true))
    }
  }

  "ZipN Source" must {
    "properly zipN" in {
      val sources = immutable.Seq(
        Source(List(1, 2, 3)),
        Source(List(10, 20, 30)),
        Source(List(100, 200, 300)))

      Source.zipN(sources)
        .runWith(Sink.seq)
        .futureValue should ===(immutable.Seq(
          immutable.Seq(1, 10, 100),
          immutable.Seq(2, 20, 200),
          immutable.Seq(3, 30, 300)))
    }
  }

  "ZipWithN Source" must {
    "properly zipWithN" in {
      val sources = immutable.Seq(
        Source(List(1, 2, 3)),
        Source(List(10, 20, 30)),
        Source(List(100, 200, 300)))

      Source.zipWithN[Int, Int](_.sum)(sources)
        .runWith(Sink.seq)
        .futureValue should ===(immutable.Seq(111, 222, 333))
    }
  }

  "Cycle Source" must {

    "continuously generate the same sequence" in {
      val expected = Seq(1, 2, 3, 1, 2, 3, 1, 2, 3)
      Source.cycle(() ⇒ List(1, 2, 3).iterator).grouped(9).runWith(Sink.head).futureValue should ===(expected)
    }

    "throw an exception in case of empty iterator" in {
      val empty = Iterator.empty
      assert(Source.cycle(() ⇒ empty).runWith(Sink.head).failed.futureValue.isInstanceOf[IllegalArgumentException])
    }
  }

  "A Source" must {
    "suitably override attribute handling methods" in {
      import Attributes._
      val s: Source[Int, NotUsed] = Source.single(42).async.addAttributes(none).named("")
    }
  }

  "Java Stream source" must {
    import scala.compat.java8.FunctionConverters._
    import java.util.stream.{ Stream, IntStream }

    def javaStreamInts = IntStream.iterate(1, { i: Int ⇒ i + 1 }.asJava)

    "work with Java collections" in {
      val list = new java.util.LinkedList[Integer]()
      list.add(0)
      list.add(1)
      list.add(2)

      StreamConverters.fromJavaStream(() ⇒ list.stream()).map(_.intValue).runWith(Sink.seq).futureValue should ===(List(0, 1, 2))
    }

    "work with primitive streams" in {
      StreamConverters.fromJavaStream(() ⇒ IntStream.rangeClosed(1, 10)).map(_.intValue).runWith(Sink.seq).futureValue should ===(1 to 10)
    }

    "work with an empty stream" in {
      StreamConverters.fromJavaStream(() ⇒ Stream.empty[Int]()).runWith(Sink.seq).futureValue should ===(Nil)
    }

    "work with an infinite stream" in {
      StreamConverters.fromJavaStream(() ⇒ javaStreamInts).take(1000).runFold(0)(_ + _).futureValue should ===(500500)
    }

    "work with a filtered stream" in {
      StreamConverters.fromJavaStream(() ⇒ javaStreamInts.filter({ i: Int ⇒ i % 2 == 0 }.asJava))
        .take(1000).runFold(0)(_ + _).futureValue should ===(1001000)
    }

    "properly report errors during iteration" in {
      import akka.stream.testkit.Utils.TE
      // Filtering is lazy on Java Stream

      val failyFilter: Int ⇒ Boolean = i ⇒ throw TE("failing filter")

      a[TE] must be thrownBy {
        Await.result(
          StreamConverters.fromJavaStream(() ⇒ javaStreamInts.filter(failyFilter.asJava)).runWith(Sink.ignore),
          3.seconds)
      }
    }

  }

}
