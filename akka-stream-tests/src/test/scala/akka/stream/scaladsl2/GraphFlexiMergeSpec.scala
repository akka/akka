package akka.stream.scaladsl2

import akka.stream.testkit.AkkaSpec
import FlowGraphImplicits._
import akka.stream.testkit.StreamTestKit.SubscriberProbe
import akka.stream.testkit.StreamTestKit.OnNext
import akka.stream.testkit.StreamTestKit.PublisherProbe
import akka.stream.testkit.StreamTestKit.AutoPublisher
import scala.util.control.NoStackTrace

object GraphFlexiMergeSpec {

  /**
   * This is fair in that sense that after dequeueing from an input it yields to other inputs if
   * they are available. Or in other words, if all inputs have elements available at the same
   * time then in finite steps all those elements are dequeued from them.
   */
  class Fair[T] extends FlexiMerge[T]("fairMerge") {
    import FlexiMerge._
    val input1 = createInputPort[T]()
    val input2 = createInputPort[T]()

    def createMergeLogic: MergeLogic[T] = new MergeLogic[T] {
      override def inputHandles(inputCount: Int) = Vector(input1, input2)
      override def initialState = State[T](ReadAny(input1, input2)) { (ctx, input, element) ⇒
        ctx.emit(element)
        SameState
      }
    }
  }

  /**
   * It never skips an input while cycling but waits on it instead (closed inputs are skipped though).
   * The fair merge above is a non-strict round-robin (skips currently unavailable inputs).
   */
  class StrictRoundRobin[T] extends FlexiMerge[T]("roundRobinMerge") {
    import FlexiMerge._
    val input1 = createInputPort[T]()
    val input2 = createInputPort[T]()

    def createMergeLogic = new MergeLogic[T] {

      override def inputHandles(inputCount: Int) = Vector(input1, input2)

      val emitOtherOnClose = CompletionHandling(
        onComplete = { (ctx, input) ⇒
          ctx.changeCompletionHandling(defaultCompletionHandling)
          readRemaining(other(input))
        },
        onError = { (ctx, _, cause) ⇒
          ctx.error(cause)
          SameState
        })

      def other(input: InputHandle): InputHandle = if (input eq input1) input2 else input1

      val read1: State[T] = State[T](Read(input1)) { (ctx, input, element) ⇒
        ctx.emit(element)
        read2
      }

      val read2 = State[T](Read(input2)) { (ctx, input, element) ⇒
        ctx.emit(element)
        read1
      }

      def readRemaining(input: InputHandle) = State[T](Read(input)) { (ctx, input, element) ⇒
        ctx.emit(element)
        SameState
      }

      override def initialState = read1

      override def initialCompletionHandling = emitOtherOnClose
    }
  }

  class Zip[A, B] extends FlexiMerge[(A, B)]("zip") {
    import FlexiMerge._
    val input1 = createInputPort[A]()
    val input2 = createInputPort[B]()

    def createMergeLogic = new MergeLogic[(A, B)] {
      var lastInA: A = _

      override def inputHandles(inputCount: Int) = {
        require(inputCount == 2, s"Zip must have two connected inputs, was $inputCount")
        Vector(input1, input2)
      }

      val readA: State[A] = State[A](Read(input1)) { (ctx, input, element) ⇒
        lastInA = element
        readB
      }

      val readB: State[B] = State[B](Read(input2)) { (ctx, input, element) ⇒
        ctx.emit((lastInA, element))
        readA
      }

      override def initialState = readA

      override def initialCompletionHandling = eagerClose
    }
  }

  class OrderedMerge extends FlexiMerge[Int] {
    import FlexiMerge._
    val input1 = createInputPort[Int]()
    val input2 = createInputPort[Int]()

    def createMergeLogic = new MergeLogic[Int] {
      private var reference = 0

      override def inputHandles(inputCount: Int) = Vector(input1, input2)

      val emitOtherOnClose = CompletionHandling(
        onComplete = { (ctx, input) ⇒
          ctx.changeCompletionHandling(emitLast)
          readRemaining(other(input))
        },
        onError = { (ctx, input, cause) ⇒
          ctx.error(cause)
          SameState
        })

      def other(input: InputHandle): InputHandle = if (input eq input1) input2 else input1

      def getFirstElement = State[Int](ReadAny(input1, input2)) { (ctx, input, element) ⇒
        reference = element
        ctx.changeCompletionHandling(emitOtherOnClose)
        readUntilLarger(other(input))
      }

      def readUntilLarger(input: InputHandle): State[Int] = State[Int](Read(input)) {
        (ctx, input, element) ⇒
          if (element <= reference) {
            ctx.emit(element)
            SameState
          } else {
            ctx.emit(reference)
            reference = element
            readUntilLarger(other(input))
          }
      }

      def readRemaining(input: InputHandle) = State[Int](Read(input)) {
        (ctx, input, element) ⇒
          if (element <= reference)
            ctx.emit(element)
          else {
            ctx.emit(reference)
            reference = element
          }
          SameState
      }

      val emitLast = CompletionHandling(
        onComplete = { (ctx, input) ⇒
          if (ctx.isDemandAvailable)
            ctx.emit(reference)
          SameState
        },
        onError = { (ctx, input, cause) ⇒
          ctx.error(cause)
          SameState
        })

      override def initialState = getFirstElement
    }
  }

  class TestMerge extends FlexiMerge[String]("testMerge") {
    import FlexiMerge._
    val input1 = createInputPort[String]()
    val input2 = createInputPort[String]()
    val input3 = createInputPort[String]()

    def createMergeLogic: MergeLogic[String] = new MergeLogic[String] {
      val handles = Vector(input1, input2, input3)
      override def inputHandles(inputCount: Int) = handles

      override def initialState = State[String](ReadAny(handles)) {
        (ctx, input, element) ⇒
          if (element == "cancel")
            ctx.cancel(input)
          else if (element == "err")
            ctx.error(new RuntimeException("err") with NoStackTrace)
          else if (element == "complete")
            ctx.complete()
          else
            ctx.emit("onInput: " + element)

          SameState
      }

      override def initialCompletionHandling = CompletionHandling(
        onComplete = { (ctx, input) ⇒
          if (ctx.isDemandAvailable)
            ctx.emit("onComplete: " + input.portIndex)
          SameState
        },
        onError = { (ctx, input, cause) ⇒
          cause match {
            case _: IllegalArgumentException ⇒ // swallow
            case _                           ⇒ ctx.error(cause)
          }
          SameState
        })
    }
  }
}

class GraphFlexiMergeSpec extends AkkaSpec {
  import GraphFlexiMergeSpec._

  implicit val materializer = FlowMaterializer()

  val in1 = Source(List("a", "b", "c", "d"))
  val in2 = Source(List("e", "f"))

  val out1 = Sink.publisher[String]

  "FlexiMerge" must {

    "build simple fair merge" in {
      val m = FlowGraph { implicit b ⇒
        val merge = new Fair[String]
        in1 ~> merge.input1 ~> out1
        in2 ~> merge.input2
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      (s.probe.receiveN(6).map { case OnNext(elem) ⇒ elem }).toSet should be(
        Set("a", "b", "c", "d", "e", "f"))
      s.expectComplete()
    }

    "build simple round robin merge" in {
      val m = FlowGraph { implicit b ⇒
        val merge = new StrictRoundRobin[String]
        in1 ~> merge.input1
        in2 ~> merge.input2
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
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
      val output = Sink.publisher[(Int, String)]
      val m = FlowGraph { implicit b ⇒
        val merge = new Zip[Int, String]
        Source(List(1, 2, 3, 4)) ~> merge.input1
        Source(List("a", "b", "c")) ~> merge.input2
        merge.out ~> output
      }.run()

      val s = SubscriberProbe[(Int, String)]
      val p = m.get(output)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext(1 -> "a")
      s.expectNext(2 -> "b")
      s.expectNext(3 -> "c")
      s.expectComplete()
    }

    "build simple ordered merge 1" in {
      val output = Sink.publisher[Int]
      val m = FlowGraph { implicit b ⇒
        val merge = new OrderedMerge
        Source(List(3, 5, 6, 7, 8)) ~> merge.input1
        Source(List(1, 2, 4, 9)) ~> merge.input2
        merge.out ~> output
      }.run()

      val s = SubscriberProbe[Int]
      val p = m.get(output)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(100)
      for (n ← 1 to 9)
        s.expectNext(n)
      s.expectComplete()
    }

    "build simple ordered merge 2" in {
      val output = Sink.publisher[Int]
      val m = FlowGraph { implicit b ⇒
        val merge = new OrderedMerge
        Source(List(3, 5, 6, 7, 8)) ~> merge.input1
        Source(List(3, 5, 6, 7, 8, 10)) ~> merge.input2
        merge.out ~> output
      }.run()

      val s = SubscriberProbe[Int]
      val p = m.get(output)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(100)
      s.expectNext(3)
      s.expectNext(3)
      s.expectNext(5)
      s.expectNext(5)
      s.expectNext(6)
      s.expectNext(6)
      s.expectNext(7)
      s.expectNext(7)
      s.expectNext(8)
      s.expectNext(8)
      s.expectNext(10)
      s.expectComplete()
    }

    "support cancel of input" in {
      val publisher = PublisherProbe[String]
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source(publisher) ~> merge.input1
        Source(List("b", "c", "d")) ~> merge.input2
        Source(List("e", "f")) ~> merge.input3
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)

      val autoPublisher = new AutoPublisher(publisher)
      autoPublisher.sendNext("a")
      autoPublisher.sendNext("cancel")

      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("onInput: a")
      s.expectNext("onInput: b")
      s.expectNext("onInput: e")
      s.expectNext("onInput: c")
      s.expectNext("onInput: f")
      s.expectNext("onComplete: 2")
      s.expectNext("onInput: d")
      s.expectNext("onComplete: 1")

      autoPublisher.sendNext("x")

      s.expectComplete()
    }

    "complete when all inputs cancelled" in {
      val publisher1 = PublisherProbe[String]
      val publisher2 = PublisherProbe[String]
      val publisher3 = PublisherProbe[String]
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source(publisher1) ~> merge.input1
        Source(publisher2) ~> merge.input2
        Source(publisher3) ~> merge.input3
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)

      val autoPublisher1 = new AutoPublisher(publisher1)
      autoPublisher1.sendNext("a")
      autoPublisher1.sendNext("cancel")

      val autoPublisher2 = new AutoPublisher(publisher2)
      autoPublisher2.sendNext("b")
      autoPublisher2.sendNext("cancel")

      val autoPublisher3 = new AutoPublisher(publisher3)
      autoPublisher3.sendNext("c")
      autoPublisher3.sendNext("cancel")

      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("onInput: a")
      s.expectNext("onInput: b")
      s.expectNext("onInput: c")
      s.expectComplete()
    }

    "handle error" in {
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source.failed[String](new IllegalArgumentException("ERROR") with NoStackTrace) ~> merge.input1
        Source(List("a", "b")) ~> merge.input2
        Source(List("c")) ~> merge.input3
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      // IllegalArgumentException is swallowed by the CompletionHandler
      s.expectNext("onInput: a")
      s.expectNext("onInput: c")
      s.expectNext("onComplete: 2")
      s.expectNext("onInput: b")
      s.expectNext("onComplete: 1")
      s.expectComplete()
    }

    "propagate error" in {
      val publisher = PublisherProbe[String]
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source(publisher) ~> merge.input1
        Source.failed[String](new IllegalStateException("ERROR") with NoStackTrace) ~> merge.input2
        Source.empty[String] ~> merge.input3
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)
      s.expectErrorOrSubscriptionFollowedByError().getMessage should be("ERROR")
    }

    "emit error" in {
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source(List("a", "err")) ~> merge.input1
        Source(List("b", "c")) ~> merge.input2
        Source.empty[String] ~> merge.input3
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("onInput: a")
      s.expectNext("onInput: b")
      s.expectError().getMessage should be("err")
    }

    "support complete from onInput" in {
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source(List("a", "complete")) ~> merge.input1
        Source(List("b", "c")) ~> merge.input2
        Source.empty[String] ~> merge.input3
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("onInput: a")
      s.expectNext("onInput: b")
      s.expectComplete()
    }

    "support unconnected inputs" in {
      val m = FlowGraph { implicit b ⇒
        val merge = new TestMerge
        Source(List("a")) ~> merge.input1
        Source(List("b", "c")) ~> merge.input2
        // input3 not connected
        merge.out ~> out1
      }.run()

      val s = SubscriberProbe[String]
      val p = m.get(out1)
      p.subscribe(s)
      val sub = s.expectSubscription()
      sub.request(10)
      s.expectNext("onInput: a")
      s.expectNext("onComplete: 0")
      s.expectNext("onInput: b")
      s.expectNext("onInput: c")
      s.expectNext("onComplete: 1")
      s.expectComplete()
    }

  }
}

