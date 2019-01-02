/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.testkit.DefaultTimeout
import org.scalatest.time.{ Millis, Span }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

import akka.stream.testkit.Utils.TE
//#imports
import akka.stream._

//#imports
import akka.stream.testkit._
import akka.NotUsed
import akka.testkit.EventFilter
import scala.collection.immutable
import java.util
import java.util.stream.BaseStream

import akka.stream.testkit.scaladsl.TestSink

class SourceSpec extends StreamSpec with DefaultTimeout {

  implicit val materializer = ActorMaterializer()
  implicit val config = PatienceConfig(timeout = Span(timeout.duration.toMillis, Millis))

  "Single Source" must {

    "produce exactly one element" in {
      implicit val ec = system.dispatcher
      //#source-single
      val s: Future[immutable.Seq[Int]] = Source.single(1).runWith(Sink.seq)
      s.foreach(list ⇒ println(s"Collected elements: $list")) // prints: Collected elements: List(1)

      //#source-single

      s.futureValue should ===(immutable.Seq(1))

    }

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

    "combine using Concat strategy two inputs with simplified API" in {
      //#combine
      val sources = immutable.Seq(
        Source(List(1, 2, 3)),
        Source(List(10, 20, 30)))

      Source.combine(sources(0), sources(1))(Concat(_))
        .runWith(Sink.seq)
        // This will produce the Seq(1, 2, 3, 10, 20, 30)
        //#combine
        .futureValue should ===(immutable.Seq(1, 2, 3, 10, 20, 30))

    }

    "combine from two inputs with combinedMat and take a materialized value" in {
      val queueSource = Source.queue[Int](1, OverflowStrategy.dropBuffer)
      val intSeqSource = Source(1 to 3)

      // compiler to check the correct materialized value of type = SourceQueueWithComplete[Int] available
      val combined1: Source[Int, SourceQueueWithComplete[Int]] =
        Source.combineMat(queueSource, intSeqSource)(Concat(_))(Keep.left) //Keep.left (i.e. preserve queueSource's materialized value)

      val (queue1, sinkProbe1) = combined1.toMat(TestSink.probe[Int])(Keep.both).run()
      sinkProbe1.request(6)
      queue1.offer(10)
      queue1.offer(20)
      queue1.offer(30)
      queue1.complete() //complete queueSource so that combined1 with `Concat` then pulls elements from intSeqSource
      sinkProbe1.expectNext(10)
      sinkProbe1.expectNext(20)
      sinkProbe1.expectNext(30)
      sinkProbe1.expectNext(1)
      sinkProbe1.expectNext(2)
      sinkProbe1.expectNext(3)

      // compiler to check the correct materialized value of type = SourceQueueWithComplete[Int] available
      val combined2: Source[Int, SourceQueueWithComplete[Int]] =
        //queueSource to be the second of combined source
        Source.combineMat(intSeqSource, queueSource)(Concat(_))(Keep.right) //Keep.right (i.e. preserve queueSource's materialized value)

      val (queue2, sinkProbe2) = combined2.toMat(TestSink.probe[Int])(Keep.both).run()
      sinkProbe2.request(6)
      queue2.offer(10)
      queue2.offer(20)
      queue2.offer(30)
      queue2.complete() //complete queueSource so that combined1 with `Concat` then pulls elements from queueSource
      sinkProbe2.expectNext(1) //as intSeqSource iss the first in combined source, elements from intSeqSource come first
      sinkProbe2.expectNext(2)
      sinkProbe2.expectNext(3)
      sinkProbe2.expectNext(10) //after intSeqSource run out elements, queueSource elements come
      sinkProbe2.expectNext(20)
      sinkProbe2.expectNext(30)
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

    "fail stream when iterator throws" in {
      Source
        .fromIterator(() ⇒ (1 to 1000).toIterator.map(k ⇒ if (k < 10) k else throw TE("a")))
        .runWith(Sink.ignore)
        .failed.futureValue.getClass should ===(classOf[TE])

      Source
        .fromIterator(() ⇒ (1 to 1000).toIterator.map(_ ⇒ throw TE("b")))
        .runWith(Sink.ignore)
        .failed.futureValue.getClass should ===(classOf[TE])
    }

    "use decider when iterator throws" in {
      Source
        .fromIterator(() ⇒ (1 to 5).toIterator.map(k ⇒ if (k != 3) k else throw TE("a")))
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
        .grouped(10)
        .runWith(Sink.head)
        .futureValue should ===(List(1, 2))

      Source
        .fromIterator(() ⇒ (1 to 5).toIterator.map(_ ⇒ throw TE("b")))
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
        .grouped(10)
        .runWith(Sink.headOption)
        .futureValue should ===(None)
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
      //#cycle
      Source.cycle(() ⇒ List(1, 2, 3).iterator)
        .grouped(9)
        .runWith(Sink.head)
        // This will produce the Seq(1, 2, 3, 1, 2, 3, 1, 2, 3)
        //#cycle
        .futureValue should ===(expected)
    }

    "throw an exception in case of empty iterator" in {
      //#cycle-error
      val empty = Iterator.empty
      Source.cycle(() ⇒ empty)
        .runWith(Sink.head)
        // This will return a failed future with an `IllegalArgumentException`
        //#cycle-error
        .failed.futureValue shouldBe an[IllegalArgumentException]
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

    "close the underlying stream when completed" in {
      @volatile var closed = false

      final class EmptyStream[A] extends BaseStream[A, EmptyStream[A]] {
        override def unordered(): EmptyStream[A] = this
        override def sequential(): EmptyStream[A] = this
        override def parallel(): EmptyStream[A] = this
        override def isParallel: Boolean = false

        override def spliterator(): util.Spliterator[A] = ???
        override def onClose(closeHandler: Runnable): EmptyStream[A] = ???

        override def iterator(): util.Iterator[A] = new util.Iterator[A] {
          override def next(): A = ???
          override def hasNext: Boolean = false
        }

        override def close(): Unit = closed = true
      }

      Await.ready(StreamConverters.fromJavaStream(() ⇒ new EmptyStream[Unit]).runWith(Sink.ignore), 3.seconds)

      closed should ===(true)
    }

    "close the underlying stream when failed" in {
      @volatile var closed = false

      final class FailingStream[A] extends BaseStream[A, FailingStream[A]] {
        override def unordered(): FailingStream[A] = this
        override def sequential(): FailingStream[A] = this
        override def parallel(): FailingStream[A] = this
        override def isParallel: Boolean = false

        override def spliterator(): util.Spliterator[A] = ???
        override def onClose(closeHandler: Runnable): FailingStream[A] = ???

        override def iterator(): util.Iterator[A] = new util.Iterator[A] {
          override def next(): A = throw new RuntimeException("ouch")
          override def hasNext: Boolean = true
        }

        override def close(): Unit = closed = true
      }

      Await.ready(StreamConverters.fromJavaStream(() ⇒ new FailingStream[Unit]).runWith(Sink.ignore), 3.seconds)

      closed should ===(true)
    }
  }

  "Source pre-materialization" must {

    "materialize the source and connect it to a publisher" in {
      val matValPoweredSource = Source.maybe[Int]
      val (mat, src) = matValPoweredSource.preMaterialize()

      val probe = src.runWith(TestSink.probe[Int])

      probe.request(1)
      mat.success(Some(42))
      probe.expectNext(42)
      probe.expectComplete()
    }

    "allow for multiple downstream materialized sources" in {
      val matValPoweredSource = Source.queue[String](Int.MaxValue, OverflowStrategy.fail)
      val (mat, src) = matValPoweredSource.preMaterialize()

      val probe1 = src.runWith(TestSink.probe[String])
      val probe2 = src.runWith(TestSink.probe[String])

      probe1.request(1)
      probe2.request(1)
      mat.offer("One").futureValue
      probe1.expectNext("One")
      probe2.expectNext("One")
    }

    "survive cancellations of downstream materialized sources" in {
      val matValPoweredSource = Source.queue[String](Int.MaxValue, OverflowStrategy.fail)
      val (mat, src) = matValPoweredSource.preMaterialize()

      val probe1 = src.runWith(TestSink.probe[String])
      src.runWith(Sink.cancelled)

      probe1.request(1)
      mat.offer("One").futureValue
      probe1.expectNext("One")
    }

    "propagate failures to downstream materialized sources" in {
      val matValPoweredSource = Source.queue[String](Int.MaxValue, OverflowStrategy.fail)
      val (mat, src) = matValPoweredSource.preMaterialize()

      val probe1 = src.runWith(TestSink.probe[String])
      val probe2 = src.runWith(TestSink.probe[String])

      mat.fail(new RuntimeException("boom"))

      probe1.expectSubscription()
      probe2.expectSubscription()

      probe1.expectError().getMessage should ===("boom")
      probe2.expectError().getMessage should ===("boom")
    }

    "correctly propagate materialization failures" in {
      val matValPoweredSource = Source.empty.mapMaterializedValue(_ ⇒ throw new RuntimeException("boom"))

      a[RuntimeException] shouldBe thrownBy(matValPoweredSource.preMaterialize())
    }
  }
}
