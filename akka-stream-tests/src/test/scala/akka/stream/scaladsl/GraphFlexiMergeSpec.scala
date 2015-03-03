/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.FlexiMerge._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.{ PublisherProbe, AutoPublisher, OnNext, SubscriberProbe }
import org.reactivestreams.Publisher
import akka.stream._
import scala.util.control.NoStackTrace
import scala.collection.immutable
import akka.actor.ActorRef
import akka.testkit.TestProbe

object GraphFlexiMergeSpec {

  class Fair[T] extends FlexiMerge[T, UniformFanInShape[T, T]](new UniformFanInShape(2), OperationAttributes.name("FairMerge")) {
    def createMergeLogic(p: PortT): MergeLogic[T] = new MergeLogic[T] {
      override def initialState = State[T](ReadAny(p.in(0), p.in(1))) { (ctx, input, element) ⇒
        ctx.emit(element)
        SameState
      }
    }
  }

  class StrictRoundRobin[T] extends FlexiMerge[T, UniformFanInShape[T, T]](new UniformFanInShape(2), OperationAttributes.name("RoundRobinMerge")) {
    def createMergeLogic(p: PortT): MergeLogic[T] = new MergeLogic[T] {
      val emitOtherOnClose = CompletionHandling(
        onUpstreamFinish = { (ctx, input) ⇒
          ctx.changeCompletionHandling(defaultCompletionHandling)
          readRemaining(other(input))
        },
        onUpstreamFailure = { (ctx, _, cause) ⇒
          ctx.fail(cause)
          SameState
        })

      def other(input: InPort): Inlet[T] = if (input eq p.in(0)) p.in(1) else p.in(0)

      val read1: State[T] = State(Read(p.in(0))) { (ctx, input, element) ⇒
        ctx.emit(element)
        read2
      }

      val read2: State[T] = State(Read(p.in(1))) { (ctx, input, element) ⇒
        ctx.emit(element)
        read1
      }

      def readRemaining(input: Inlet[T]) = State(Read(input)) { (ctx, input, element) ⇒
        ctx.emit(element)
        SameState
      }

      override def initialState = read1

      override def initialCompletionHandling = emitOtherOnClose
    }
  }

  class MyZip[A, B] extends FlexiMerge[(A, B), FanInShape2[A, B, (A, B)]](new FanInShape2("MyZip"), OperationAttributes.name("MyZip")) {
    def createMergeLogic(p: PortT): MergeLogic[(A, B)] = new MergeLogic[(A, B)] {
      var lastInA: A = _

      val readA: State[A] = State[A](Read(p.in0)) { (ctx, input, element) ⇒
        lastInA = element
        readB
      }

      val readB: State[B] = State[B](Read(p.in1)) { (ctx, input, element) ⇒
        ctx.emit((lastInA, element))
        readA
      }

      override def initialCompletionHandling = eagerClose

      override def initialState: State[_] = readA
    }
  }

  class TripleCancellingZip[A, B, C](var cancelAfter: Int = Int.MaxValue, defVal: Option[A] = None)
    extends FlexiMerge[(A, B, C), FanInShape3[A, B, C, (A, B, C)]](new FanInShape3("TripleCancellingZip"), OperationAttributes.name("TripleCancellingZip")) {
    def createMergeLogic(p: PortT) = new MergeLogic[(A, B, C)] {
      override def initialState = State(ReadAll(p.in0, p.in1, p.in2)) {
        case (ctx, input, inputs) ⇒
          val a = inputs.getOrElse(p.in0, defVal.get)
          val b = inputs(p.in1)
          val c = inputs(p.in2)

          ctx.emit((a, b, c))
          if (cancelAfter == 0)
            ctx.cancel(p.in0)
          cancelAfter -= 1

          SameState
      }

      override def initialCompletionHandling = eagerClose
    }
  }

  object PreferringMerge extends FlexiMerge[Int, UniformFanInShape[Int, Int]](new UniformFanInShape(3), OperationAttributes.name("PreferringMerge")) {
    def createMergeLogic(p: PortT) = new MergeLogic[Int] {
      override def initialState = State(Read(p.in(0))) {
        (ctx, input, element) ⇒
          ctx.emit(element)
          running
      }
      val running = State(ReadPreferred(p.in(0), p.in(1), p.in(2))) {
        (ctx, input, element) ⇒
          ctx.emit(element)
          SameState
      }
    }
  }

  class TestMerge(completionProbe: ActorRef)
    extends FlexiMerge[String, UniformFanInShape[String, String]](new UniformFanInShape(3), OperationAttributes.name("TestMerge")) {

    def createMergeLogic(p: PortT) = new MergeLogic[String] {
      var throwFromOnComplete = false

      override def initialState = State(ReadAny(p.inArray: _*)) {
        (ctx, input, element) ⇒
          if (element == "cancel")
            ctx.cancel(input)
          else if (element == "err")
            ctx.fail(new RuntimeException("err") with NoStackTrace)
          else if (element == "exc")
            throw new RuntimeException("exc") with NoStackTrace
          else if (element == "complete")
            ctx.finish()
          else if (element == "onUpstreamFinish-exc")
            throwFromOnComplete = true
          else
            ctx.emit("onInput: " + element)

          SameState
      }

      override def initialCompletionHandling = CompletionHandling(
        onUpstreamFinish = { (ctx, input) ⇒
          if (throwFromOnComplete)
            throw new RuntimeException("onUpstreamFinish-exc") with NoStackTrace
          completionProbe ! input.toString
          SameState
        },
        onUpstreamFailure = { (ctx, input, cause) ⇒
          cause match {
            case _: IllegalArgumentException ⇒ // swallow
            case _                           ⇒ ctx.fail(cause)
          }
          SameState
        })
    }
  }

}

class GraphFlexiMergeSpec extends AkkaSpec {
  import GraphFlexiMergeSpec._
  import FlowGraph.Implicits._

  implicit val materializer = ActorFlowMaterializer()

  val in1 = Source(List("a", "b", "c", "d"))
  val in2 = Source(List("e", "f"))

  val out = Sink.publisher[String]

  val fairString = new Fair[String]

  "FlexiMerge" must {

    "build simple fair merge" in {
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(fairString)

          in1 ~> merge.in(0)
          in2 ~> merge.in(1)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      (s.probe.receiveN(6).map { case OnNext(elem) ⇒ elem }).toSet should be(
        Set("a", "b", "c", "d", "e", "f"))
      s.expectComplete()
    }

    "be able to have two fleximerges in a graph" in {
      val p = FlowGraph.closed(in1, in2, out)((i1, i2, o) ⇒ o) { implicit b ⇒
        (in1, in2, o) ⇒
          val m1 = b.add(fairString)
          val m2 = b.add(fairString)

          // format: OFF
          in1.outlet ~> m1.in(0)
          in2.outlet ~> m1.in(1)

          Source(List("A", "B", "C", "D", "E", "F")) ~> m2.in(0)
                                              m1.out ~> m2.in(1)
                                                        m2.out ~> o.inlet
        // format: ON
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(20)
      (s.probe.receiveN(12).map { case OnNext(elem) ⇒ elem }).toSet should be(
        Set("a", "b", "c", "d", "e", "f", "A", "B", "C", "D", "E", "F"))
      s.expectComplete()
    }

    "allow reuse" in {
      val flow = Flow() { implicit b ⇒
        val merge = b.add(new Fair[String])

        Source(() ⇒ Iterator.continually("+")) ~> merge.in(0)

        merge.in(1) → merge.out
      }

      val g = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val zip = b add Zip[String, String]()
          in1 ~> flow ~> Flow[String].map { of ⇒ of } ~> zip.in0
          in2 ~> flow ~> Flow[String].map { tf ⇒ tf } ~> zip.in1
          zip.out.map { x ⇒ x.toString } ~> o.inlet
      }

      val p = g.run()
      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(1000)
      val received = s.probe.receiveN(1000).map { case OnNext(elem: String) ⇒ elem }
      val first = received.map(_.charAt(1))
      first.toSet should ===(Set('a', 'b', 'c', 'd', '+'))
      first.filter(_ != '+') should ===(Seq('a', 'b', 'c', 'd'))
      val second = received.map(_.charAt(3))
      second.toSet should ===(Set('e', 'f', '+'))
      second.filter(_ != '+') should ===(Seq('e', 'f'))
      sub.cancel()
    }

    "allow zip reuse" in {
      val flow = Flow() { implicit b ⇒
        val zip = b.add(new MyZip[String, String])

        Source(() ⇒ Iterator.continually("+")) ~> zip.in0

        (zip.in1, zip.out)
      }

      val g = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val zip = b.add(Zip[String, String]())

          in1 ~> flow.map(_.toString()) ~> zip.in0
          in2 ~> zip.in1

          zip.out.map(_.toString()) ~> o.inlet
      }

      val p = g.run()
      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(100)
      (s.probe.receiveN(2).map { case OnNext(elem) ⇒ elem }).toSet should be(Set("((+,b),f)", "((+,a),e)"))
      s.expectComplete()
    }

    "build simple round robin merge" in {
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new StrictRoundRobin[String])
          in1 ~> merge.in(0)
          in2 ~> merge.in(1)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("a")
      s.expectNext("e")
      s.expectNext("b")
      s.expectNext("f")
      s.expectNext("c")
      s.expectNext("d")
      s.expectComplete()
    }

    "build simple zip merge" in {
      val p = FlowGraph.closed(Sink.publisher[(Int, String)]) { implicit b ⇒
        o ⇒
          val merge = b.add(new MyZip[Int, String])
          Source(List(1, 2, 3, 4)) ~> merge.in0
          Source(List("a", "b", "c")) ~> merge.in1
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[(Int, String)]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext(1 -> "a")
      s.expectNext(2 -> "b")
      s.expectNext(3 -> "c")
      s.expectComplete()
    }

    "build simple triple-zip merge using ReadAll" in {
      val p = FlowGraph.closed(Sink.publisher[(Long, Int, String)]) { implicit b ⇒
        o ⇒
          val merge = b.add(new TripleCancellingZip[Long, Int, String])
        // format: OFF
        Source(List(1L,   2L       )) ~> merge.in0
        Source(List(1,    2,   3, 4)) ~> merge.in1
        Source(List("a", "b", "c"  )) ~> merge.in2
        merge.out ~> o.inlet
        // format: ON
      }.run()

      val s = SubscriberProbe[(Long, Int, String)]
      p.subscribe(s)
      val sub = s.expectSubscription()

      sub.request(10)
      s.expectNext((1L, 1, "a"))
      s.expectNext((2L, 2, "b"))
      s.expectComplete()
    }

    "build simple triple-zip merge using ReadAll, and continue with provided value for cancelled input" in {
      val p = FlowGraph.closed(Sink.publisher[(Long, Int, String)]) { implicit b ⇒
        o ⇒
          val merge = b.add(new TripleCancellingZip[Long, Int, String](1, Some(0L)))
        // format: OFF
        Source(List(1L,   2L,  3L,  4L, 5L)) ~> merge.in0
        Source(List(1,    2,   3,   4     )) ~> merge.in1
        Source(List("a", "b", "c"         )) ~> merge.in2
        merge.out ~> o.inlet
        // format: ON
      }.run()

      val s = SubscriberProbe[(Long, Int, String)]
      p.subscribe(s)
      val sub = s.expectSubscription()

      sub.request(10)
      s.expectNext((1L, 1, "a"))
      s.expectNext((2L, 2, "b"))
      // soonCancelledInput is now cancelled and continues with default (null) value
      s.expectNext((0L, 3, "c"))
      s.expectComplete()
    }

    "build perferring merge" in {
      val output = Sink.publisher[Int]
      val p = FlowGraph.closed(output) { implicit b ⇒
        o ⇒
          val merge = b.add(PreferringMerge)
          Source(List(1, 2, 3)) ~> merge.in(0)
          Source(List(11, 12, 13)) ~> merge.in(1)
          Source(List(14, 15, 16)) ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[Int]
      p.subscribe(s)
      val sub = s.expectSubscription()

      def expect(i: Int): Unit = {
        sub.request(1)
        s.expectNext(i)
      }
      def expectNext(): Int = {
        sub.request(1)
        s.expectNext()
      }

      expect(1)
      expect(2)
      expect(3)
      val secondaries = expectNext() ::
        expectNext() ::
        expectNext() ::
        expectNext() ::
        expectNext() ::
        expectNext() :: Nil

      secondaries.toSet should equal(Set(11, 12, 13, 14, 15, 16))
      s.expectComplete()
    }

    "build perferring merge, manually driven" in {
      val output = Sink.publisher[Int]
      val preferredDriver = PublisherProbe[Int]()
      val otherDriver1 = PublisherProbe[Int]()
      val otherDriver2 = PublisherProbe[Int]()

      val p = FlowGraph.closed(output) { implicit b ⇒
        o ⇒
          val merge = b.add(PreferringMerge)
          Source(preferredDriver) ~> merge.in(0)
          Source(otherDriver1) ~> merge.in(1)
          Source(otherDriver2) ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[Int]
      p.subscribe(s)

      val sub = s.expectSubscription()
      val p1 = preferredDriver.expectSubscription()
      val s1 = otherDriver1.expectSubscription()
      val s2 = otherDriver2.expectSubscription()

      // just consume the preferred
      p1.sendNext(1)
      sub.request(1)
      s.expectNext(1)

      // pick preferred over any of the secondaries
      p1.sendNext(2)
      s1.sendNext(10)
      s2.sendNext(20)
      sub.request(1)
      s.expectNext(2)

      sub.request(2)
      Set(s.expectNext(), s.expectNext()) should ===(Set(10, 20))

      p1.sendComplete()

      // continue with just secondaries when preferred has completed
      s1.sendNext(11)
      s2.sendNext(21)
      sub.request(2)
      Set(s.expectNext(), s.expectNext()) should ===(Set(11, 21))

      // continue with just one secondary
      s1.sendComplete()
      s2.sendNext(4)
      sub.request(1)
      s.expectNext(4)
      s2.sendComplete()

      // finish when all inputs have completed
      s.expectComplete()
    }

    "support cancel of input" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(publisher) ~> merge.in(0)
          Source(List("b", "c", "d")) ~> merge.in(1)
          Source(List("e", "f")) ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)

      val autoPublisher = new AutoPublisher(publisher)
      autoPublisher.sendNext("a")
      autoPublisher.sendNext("cancel")

      val sub = s.expectSubscription()
      sub.request(10)
      val outputs =
        for (_ ← 1 to 6) yield {
          val next = s.expectNext()
          if (next.startsWith("onInput: ")) next.substring(9) else next.substring(12)
        }
      val one = Seq("a")
      val two = Seq("b", "c", "d")
      val three = Seq("e", "f")
      outputs.filter(one.contains) should ===(one)
      outputs.filter(two.contains) should ===(two)
      outputs.filter(three.contains) should ===(three)
      completionProbe.expectMsgAllOf("UniformFanIn.in1", "UniformFanIn.in2")

      autoPublisher.sendNext("x")

      s.expectComplete()
    }

    "finish when all inputs cancelled" in {
      val publisher1 = PublisherProbe[String]
      val publisher2 = PublisherProbe[String]
      val publisher3 = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(publisher1) ~> merge.in(0)
          Source(publisher2) ~> merge.in(1)
          Source(publisher3) ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)

      val autoPublisher1 = new AutoPublisher(publisher1)
      autoPublisher1.sendNext("a")
      autoPublisher1.sendNext("cancel")
      s.expectNext("onInput: a")

      val autoPublisher2 = new AutoPublisher(publisher2)
      autoPublisher2.sendNext("b")
      autoPublisher2.sendNext("cancel")
      s.expectNext("onInput: b")

      val autoPublisher3 = new AutoPublisher(publisher3)
      autoPublisher3.sendNext("c")
      autoPublisher3.sendNext("cancel")
      s.expectNext("onInput: c")

      s.expectComplete()
    }

    "handle failure" in {
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source.failed[String](new IllegalArgumentException("ERROR") with NoStackTrace) ~> merge.in(0)
          Source(List("a", "b")) ~> merge.in(1)
          Source(List("c")) ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      // IllegalArgumentException is swallowed by the CompletionHandler
      val outputs =
        for (_ ← 1 to 3) yield {
          val next = s.expectNext()
          if (next.startsWith("onInput: ")) next.substring(9) else next.substring(12)
        }
      val one = Seq("a", "b")
      val two = Seq("c")
      completionProbe.expectMsgAllOf("UniformFanIn.in1", "UniformFanIn.in2")
      outputs.filter(one.contains) should ===(one)
      outputs.filter(two.contains) should ===(two)

      s.expectComplete()
    }

    "propagate failure" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(publisher) ~> merge.in(0)
          Source.failed[String](new IllegalStateException("ERROR") with NoStackTrace) ~> merge.in(1)
          Source.empty[String] ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      s.expectSubscriptionAndError().getMessage should be("ERROR")
    }

    "emit failure" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(List("err")) ~> merge.in(0)
          Source(publisher) ~> merge.in(1)
          Source.empty[String] ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)

      s.expectError().getMessage should be("err")
    }

    "emit failure for user thrown exception" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(List("exc")) ~> merge.in(0)
          Source(publisher) ~> merge.in(1)
          Source.empty[String] ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectError().getMessage should be("exc")
    }

    "emit failure for user thrown exception in onComplete" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(List("onUpstreamFinish-exc")) ~> merge.in(0)
          Source(publisher) ~> merge.in(1)
          Source.empty[String] ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectError().getMessage should be("onUpstreamFinish-exc")
    }

    "emit failure for user thrown exception in onUpstreamFinish 2" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source.empty[String] ~> merge.in(0)
          Source(publisher) ~> merge.in(1)
          Source.empty[String] ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val autoPublisher = new AutoPublisher(publisher)
      autoPublisher.sendNext("onUpstreamFinish-exc")
      autoPublisher.sendNext("a")

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(1)
      s.expectNext("onInput: a")

      autoPublisher.sendComplete()
      s.expectError().getMessage should be("onUpstreamFinish-exc")
    }

    "support finish from onInput" in {
      val publisher = PublisherProbe[String]
      val completionProbe = TestProbe()
      val p = FlowGraph.closed(out) { implicit b ⇒
        o ⇒
          val merge = b.add(new TestMerge(completionProbe.ref))
          Source(List("a", "complete")) ~> merge.in(0)
          Source(publisher) ~> merge.in(1)
          Source.empty[String] ~> merge.in(2)
          merge.out ~> o.inlet
      }.run()

      val s = SubscriberProbe[String]
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("onInput: a")
      s.expectComplete()
    }

  }

}
