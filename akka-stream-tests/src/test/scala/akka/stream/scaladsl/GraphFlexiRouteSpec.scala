package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import FlowGraphImplicits._
import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.AutoPublisher
import akka.stream.testkit.StreamTestKit.OnNext
import akka.stream.testkit.StreamTestKit.PublisherProbe
import akka.stream.testkit.StreamTestKit.SubscriberProbe
import akka.actor.ActorSystem

object GraphFlexiRouteSpec {

  /**
   * This is fair in that sense that after enqueueing to an output it yields to other output if
   * they are have requested elements. Or in other words, if all outputs have demand available at the same
   * time then in finite steps all elements are enqueued to them.
   */
  class Fair[T] extends FlexiRoute[T]("fairRoute") {
    import FlexiRoute._
    val out1 = createOutputPort[T]()
    val out2 = createOutputPort[T]()

    override def createRouteLogic: RouteLogic[T] = new RouteLogic[T] {
      override def outputHandles(outputCount: Int) = Vector(out1, out2)

      val emitToAnyWithDemand = State[T](DemandFromAny(out1, out2)) { (ctx, preferredOutput, element) ⇒
        ctx.emit(preferredOutput, element)
        SameState
      }

      // initally, wait for demand from all
      override def initialState = State[T](DemandFromAll(out1, out2)) { (ctx, preferredOutput, element) ⇒
        ctx.emit(preferredOutput, element)
        emitToAnyWithDemand
      }
    }
  }

  /**
   * It never skips an output while cycling but waits on it instead (closed outputs are skipped though).
   * The fair route above is a non-strict round-robin (skips currently unavailable outputs).
   */
  class StrictRoundRobin[T] extends FlexiRoute[T]("roundRobinRoute") {
    import FlexiRoute._
    val out1 = createOutputPort[T]()
    val out2 = createOutputPort[T]()

    override def createRouteLogic = new RouteLogic[T] {

      override def outputHandles(outputCount: Int) = Vector(out1, out2)

      val toOutput1: State[T] = State[T](DemandFrom(out1)) { (ctx, _, element) ⇒
        ctx.emit(out1, element)
        toOutput2
      }

      val toOutput2 = State[T](DemandFrom(out2)) { (ctx, _, element) ⇒
        ctx.emit(out2, element)
        toOutput1
      }

      override def initialState = toOutput1
    }
  }

  class Unzip[A, B] extends FlexiRoute[(A, B)]("unzip") {
    import FlexiRoute._
    val outA = createOutputPort[A]()
    val outB = createOutputPort[B]()

    override def createRouteLogic() = new RouteLogic[(A, B)] {

      override def outputHandles(outputCount: Int) = {
        require(outputCount == 2, s"Unzip must have two connected outputs, was $outputCount")
        Vector(outA, outB)
      }

      override def initialState = State[Any](DemandFromAll(outA, outB)) { (ctx, _, element) ⇒
        val (a, b) = element
        ctx.emit(outA, a)
        ctx.emit(outB, b)
        SameState
      }

      override def initialCompletionHandling = eagerClose
    }
  }

  class TestRoute extends FlexiRoute[String]("testRoute") {
    import FlexiRoute._
    val output1 = createOutputPort[String]()
    val output2 = createOutputPort[String]()
    val output3 = createOutputPort[String]()

    def createRouteLogic: RouteLogic[String] = new RouteLogic[String] {
      val handles = Vector(output1, output2, output3)
      override def outputHandles(outputCount: Int) = handles

      override def initialState = State[String](DemandFromAny(handles)) {
        (ctx, preferred, element) ⇒
          if (element == "err")
            ctx.error(new RuntimeException("err") with NoStackTrace)
          else if (element == "err-output1")
            ctx.error(output1, new RuntimeException("err-1") with NoStackTrace)
          else if (element == "complete")
            ctx.complete()
          else
            ctx.emit(preferred, "onInput: " + element)

          SameState
      }

      override def initialCompletionHandling = CompletionHandling(
        onComplete = { ctx ⇒
          handles.foreach { output ⇒
            if (ctx.isDemandAvailable(output))
              ctx.emit(output, "onComplete")
          }
        },
        onError = { (ctx, cause) ⇒
          cause match {
            case _: IllegalArgumentException ⇒ // swallow
            case _ ⇒
              handles.foreach { output ⇒
                if (ctx.isDemandAvailable(output))
                  ctx.emit(output, "onError")
              }
          }
        },
        onCancel = { (ctx, cancelledOutput) ⇒
          handles.foreach { output ⇒
            if (output != cancelledOutput && ctx.isDemandAvailable(output))
              ctx.emit(output, "onCancel: " + cancelledOutput.portIndex)
          }
          SameState
        })
    }
  }

  class TestFixture(implicit val system: ActorSystem, implicit val materializer: FlowMaterializer) {
    val publisher = PublisherProbe[String]
    val s1 = SubscriberProbe[String]
    val s2 = SubscriberProbe[String]
    FlowGraph { implicit b ⇒
      val route = new TestRoute
      Source(publisher) ~> route.in
      route.output1 ~> Sink(s1)
      route.output2 ~> Sink(s2)
    }.run()

    val autoPublisher = new AutoPublisher(publisher)
    autoPublisher.sendNext("a")
    autoPublisher.sendNext("b")

    val sub1 = s1.expectSubscription()
    val sub2 = s2.expectSubscription()
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class GraphFlexiRouteSpec extends AkkaSpec {
  import GraphFlexiRouteSpec._

  implicit val materializer = FlowMaterializer()

  val in = Source(List("a", "b", "c", "d", "e"))

  val out1 = Sink.publisher[String]
  val out2 = Sink.publisher[String]

  "FlexiRoute" must {

    "build simple fair route" in {
      val m = FlowGraph { implicit b ⇒
        val route = new Fair[String]
        in ~> route.in
        route.out1 ~> out1
        route.out2 ~> out2
      }.run()

      val s1 = SubscriberProbe[String]
      val p1 = m.get(out1)
      p1.subscribe(s1)
      val sub1 = s1.expectSubscription()
      val s2 = SubscriberProbe[String]
      val p2 = m.get(out2)
      p2.subscribe(s2)
      val sub2 = s2.expectSubscription()

      sub1.request(10)
      sub2.request(10)

      s1.expectNext("a")
      s2.expectNext("b")
      s1.expectNext("c")
      s2.expectNext("d")
      s1.expectNext("e")

      s1.expectComplete()
      s2.expectComplete()
    }

    "build simple round-robin route" in {
      val m = FlowGraph { implicit b ⇒
        val route = new StrictRoundRobin[String]
        in ~> route.in
        route.out1 ~> out1
        route.out2 ~> out2
      }.run()

      val s1 = SubscriberProbe[String]
      val p1 = m.get(out1)
      p1.subscribe(s1)
      val sub1 = s1.expectSubscription()
      val s2 = SubscriberProbe[String]
      val p2 = m.get(out2)
      p2.subscribe(s2)
      val sub2 = s2.expectSubscription()

      sub1.request(10)
      sub2.request(10)

      s1.expectNext("a")
      s2.expectNext("b")
      s1.expectNext("c")
      s2.expectNext("d")
      s1.expectNext("e")

      s1.expectComplete()
      s2.expectComplete()
    }

    "build simple unzip route" in {
      val outA = Sink.publisher[Int]
      val outB = Sink.publisher[String]

      val m = FlowGraph { implicit b ⇒
        val route = new Unzip[Int, String]
        Source(List(1 -> "A", 2 -> "B", 3 -> "C", 4 -> "D")) ~> route.in
        route.outA ~> outA
        route.outB ~> outB
      }.run()

      val s1 = SubscriberProbe[Int]
      val p1 = m.get(outA)
      p1.subscribe(s1)
      val sub1 = s1.expectSubscription()
      val s2 = SubscriberProbe[String]
      val p2 = m.get(outB)
      p2.subscribe(s2)
      val sub2 = s2.expectSubscription()

      sub1.request(3)
      sub2.request(4)

      s1.expectNext(1)
      s2.expectNext("A")
      s1.expectNext(2)
      s2.expectNext("B")
      s1.expectNext(3)
      s2.expectNext("C")
      sub1.cancel()

      s2.expectComplete()
    }

    "support complete of downstreams and cancel of upstream" in {
      val fixture = new TestFixture
      import fixture._

      autoPublisher.sendNext("complete")

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(2)
      s2.expectNext("onInput: b")

      s1.expectComplete()
      s2.expectComplete()
    }

    "support error of outputs" in {
      val fixture = new TestFixture
      import fixture._

      autoPublisher.sendNext("err")

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(2)
      s2.expectNext("onInput: b")

      s1.expectError().getMessage should be("err")
      s2.expectError().getMessage should be("err")
      autoPublisher.subscription.expectCancellation()
    }

    "support error of a specific output" in {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(5)
      sub2.request(5)
      autoPublisher.sendNext("err-output1")
      autoPublisher.sendNext("c")

      s2.expectNext("onInput: c")
      s1.expectError().getMessage should be("err-1")

      autoPublisher.sendComplete()
      s2.expectNext("onComplete")
      s2.expectComplete()
    }

    "handle cancel from output" in {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      sub1.cancel()

      s2.expectNext("onCancel: 0")
      s1.expectNoMsg(200.millis)

      autoPublisher.sendNext("c")
      s2.expectNext("onInput: c")

      autoPublisher.sendComplete()
      s2.expectComplete()
    }

    "handle complete from upstream input" in {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      autoPublisher.sendComplete()

      s1.expectNext("onComplete")
      s2.expectNext("onComplete")

      s1.expectComplete()
      s2.expectComplete()
    }

    "handle error from upstream input" in {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      autoPublisher.sendError(new RuntimeException("test err") with NoStackTrace)

      s1.expectNext("onError")
      s2.expectNext("onError")

      s1.expectError().getMessage should be("test err")
      s2.expectError().getMessage should be("test err")
    }

    "cancel upstream input when all outputs cancelled" in {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      sub1.cancel()

      s2.expectNext("onCancel: 0")
      sub2.cancel()

      autoPublisher.subscription.expectCancellation()
    }

    "cancel upstream input when all outputs completed" in {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      autoPublisher.sendNext("complete")
      s1.expectComplete()
      s2.expectComplete()
      autoPublisher.subscription.expectCancellation()
    }

  }
}

