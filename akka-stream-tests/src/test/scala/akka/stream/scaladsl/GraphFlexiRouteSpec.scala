package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import FlowGraph.Implicits._
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.stream.testkit.Utils._
import akka.actor.ActorSystem
import akka.stream._
import akka.actor.ActorRef
import akka.testkit.TestProbe

object GraphFlexiRouteSpec {

  /**
   * This is fair in that sense that after enqueueing to an output it yields to other output if
   * they are have requested elements. Or in other words, if all outputs have demand available at the same
   * time then in finite steps all elements are enqueued to them.
   */
  class Fair[T] extends FlexiRoute[T, UniformFanOutShape[T, T]](new UniformFanOutShape(2), OperationAttributes.name("FairBalance")) {
    import FlexiRoute._

    override def createRouteLogic(p: PortT): RouteLogic[T] = new RouteLogic[T] {
      val select = p.out(0) | p.out(1)

      val emitToAnyWithDemand = State(DemandFromAny(p)) { (ctx, out, element) ⇒
        ctx.emit(select(out))(element)
        SameState
      }

      // initally, wait for demand from all
      override def initialState = State(DemandFromAll(p)) { (ctx, _, element) ⇒
        ctx.emit(p.out(0))(element)
        emitToAnyWithDemand
      }
    }
  }

  /**
   * It never skips an output while cycling but waits on it instead (closed outputs are skipped though).
   * The fair route above is a non-strict round-robin (skips currently unavailable outputs).
   */
  class StrictRoundRobin[T] extends FlexiRoute[T, UniformFanOutShape[T, T]](new UniformFanOutShape(2), OperationAttributes.name("RoundRobinBalance")) {
    import FlexiRoute._

    override def createRouteLogic(p: PortT) = new RouteLogic[T] {

      val toOutput1: State[Outlet[T]] = State(DemandFrom(p.out(0))) { (ctx, out, element) ⇒
        ctx.emit(out)(element)
        toOutput2
      }

      val toOutput2 = State(DemandFrom(p.out(1))) { (ctx, out, element) ⇒
        ctx.emit(out)(element)
        toOutput1
      }

      override def initialState = toOutput1
    }
  }

  class Unzip[A, B] extends FlexiRoute[(A, B), FanOutShape2[(A, B), A, B]](new FanOutShape2("Unzip"), OperationAttributes.name("Unzip")) {
    import FlexiRoute._

    override def createRouteLogic(p: PortT) = new RouteLogic[(A, B)] {

      override def initialState = State(DemandFromAll(p)) { (ctx, _, element) ⇒
        val (a, b) = element
        ctx.emit(p.out0)(a)
        ctx.emit(p.out1)(b)
        SameState
      }

      override def initialCompletionHandling = eagerClose
    }
  }

  class StartStopTestRoute(lifecycleProbe: ActorRef)
    extends FlexiRoute[String, FanOutShape2[String, String, String]](new FanOutShape2("StartStopTest"), OperationAttributes.name("StartStopTest")) {
    import FlexiRoute._

    def createRouteLogic(p: PortT) = new RouteLogic[String] {
      val select = p.out0 | p.out1

      override def preStart(): Unit = lifecycleProbe ! "preStart"
      override def postStop(): Unit = lifecycleProbe ! "postStop"

      override def initialState = State(DemandFromAny(p)) {
        (ctx, port, element) ⇒
          lifecycleProbe ! element
          if (element == "fail") throw new IllegalStateException("test failure")
          ctx.emit(select(port))(element)

          SameState
      }
    }

  }

  class TestRoute(completionProbe: ActorRef)
    extends FlexiRoute[String, FanOutShape2[String, String, String]](new FanOutShape2("TestRoute"), OperationAttributes.name("TestRoute")) {
    import FlexiRoute._

    var throwFromOnComplete = false

    def createRouteLogic(p: PortT): RouteLogic[String] = new RouteLogic[String] {
      val select = p.out0 | p.out1

      override def initialState = State(DemandFromAny(p)) {
        (ctx, preferred, element) ⇒
          if (element == "err")
            ctx.fail(new RuntimeException("err") with NoStackTrace)
          else if (element == "err-output1")
            ctx.fail(p.out0, new RuntimeException("err-1") with NoStackTrace)
          else if (element == "exc")
            throw new RuntimeException("exc") with NoStackTrace
          else if (element == "onUpstreamFinish-exc")
            throwFromOnComplete = true
          else if (element == "finish")
            ctx.finish()
          else
            ctx.emit(select(preferred))("onInput: " + element)

          SameState
      }

      override def initialCompletionHandling = CompletionHandling(
        onUpstreamFinish = { ctx ⇒
          if (throwFromOnComplete)
            throw new RuntimeException("onUpstreamFinish-exc") with NoStackTrace
          completionProbe ! "onUpstreamFinish"
        },
        onUpstreamFailure = { (ctx, cause) ⇒
          cause match {
            case _: IllegalArgumentException ⇒ // swallow
            case _ ⇒
              completionProbe ! "onError"
          }
        },
        onDownstreamFinish = { (ctx, cancelledOutput) ⇒
          completionProbe ! "onDownstreamFinish: " + cancelledOutput
          SameState
        })
    }
  }

  class TestFixture(implicit val system: ActorSystem, implicit val materializer: ActorFlowMaterializer) {
    val autoPublisher = TestPublisher.probe[String]()
    val s1 = TestSubscriber.manualProbe[String]
    val s2 = TestSubscriber.manualProbe[String]
    val completionProbe = TestProbe()
    FlowGraph.closed() { implicit b ⇒
      val route = b.add(new TestRoute(completionProbe.ref))
      Source(autoPublisher) ~> route.in
      route.out0 ~> Sink(s1)
      route.out1 ~> Sink(s2)
    }.run()

    autoPublisher.sendNext("a")
    autoPublisher.sendNext("b")

    val sub1 = s1.expectSubscription()
    val sub2 = s2.expectSubscription()
  }

}

class GraphFlexiRouteSpec extends AkkaSpec {
  import GraphFlexiRouteSpec._

  implicit val materializer = ActorFlowMaterializer()

  val in = Source(List("a", "b", "c", "d", "e"))

  val out1 = Sink.publisher[String]
  val out2 = Sink.publisher[String]

  "FlexiRoute" must {

    "build simple fair route" in assertAllStagesStopped {
      // we can't know exactly which elements that go to each output, because if subscription/request
      // from one of the downstream is delayed the elements will be pushed to the other output
      FlowGraph.closed(TestSink.probe[String]) { implicit b ⇒
        out ⇒
          val merge = b.add(Merge[String](2))
          val route = b.add(new Fair[String])
          in ~> route.in
          route.out(0) ~> merge.in(0)
          route.out(1) ~> merge.in(1)
          merge.out ~> out
      }.run()
        .request(10)
        .expectNextUnordered("a", "b", "c", "d", "e")
        .expectComplete()
    }

    "build simple round-robin route" in {
      val (p1, p2) = FlowGraph.closed(out1, out2)(Keep.both) { implicit b ⇒
        (o1, o2) ⇒
          val route = b.add(new StrictRoundRobin[String])
          in ~> route.in
          route.out(0) ~> o1.inlet
          route.out(1) ~> o2.inlet
      }.run()

      val s1 = TestSubscriber.manualProbe[String]
      p1.subscribe(s1)
      val sub1 = s1.expectSubscription()
      val s2 = TestSubscriber.manualProbe[String]
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

      val (p1, p2) = FlowGraph.closed(outA, outB)(Keep.both) { implicit b ⇒
        (oa, ob) ⇒
          val route = b.add(new Unzip[Int, String])
          Source(List(1 -> "A", 2 -> "B", 3 -> "C", 4 -> "D")) ~> route.in
          route.out0 ~> oa.inlet
          route.out1 ~> ob.inlet
      }.run()

      val s1 = TestSubscriber.manualProbe[Int]
      p1.subscribe(s1)
      val sub1 = s1.expectSubscription()
      val s2 = TestSubscriber.manualProbe[String]
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

    "support finish of downstreams and cancel of upstream" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      autoPublisher.sendNext("finish")

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(2)
      s2.expectNext("onInput: b")

      s1.expectComplete()
      s2.expectComplete()
    }

    "support error of outputs" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      autoPublisher.sendNext("err")

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(2)
      s2.expectNext("onInput: b")

      s1.expectError().getMessage should be("err")
      s2.expectError().getMessage should be("err")
      autoPublisher.expectCancellation()
    }

    "support error of a specific output" in assertAllStagesStopped {
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
      completionProbe.expectMsg("onUpstreamFinish")
      s2.expectComplete()
    }

    "emit error for user thrown exception" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(5)
      sub2.request(5)
      autoPublisher.sendNext("exc")

      s1.expectError().getMessage should be("exc")
      s2.expectError().getMessage should be("exc")

      autoPublisher.expectCancellation()
    }

    "emit error for user thrown exception in onUpstreamFinish" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(5)
      sub2.request(5)
      autoPublisher.sendNext("onUpstreamFinish-exc")
      autoPublisher.sendComplete()

      s1.expectError().getMessage should be("onUpstreamFinish-exc")
      s2.expectError().getMessage should be("onUpstreamFinish-exc")
    }

    "handle cancel from output" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      sub1.cancel()

      completionProbe.expectMsg("onDownstreamFinish: TestRoute.out0")
      s1.expectNoMsg(200.millis)

      autoPublisher.sendNext("c")
      s2.expectNext("onInput: c")

      autoPublisher.sendComplete()
      s2.expectComplete()
    }

    "handle finish from upstream input" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      autoPublisher.sendComplete()

      completionProbe.expectMsg("onUpstreamFinish")

      s1.expectComplete()
      s2.expectComplete()
    }

    "handle error from upstream input" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      autoPublisher.sendError(new RuntimeException("test err") with NoStackTrace)

      completionProbe.expectMsg("onError")

      s1.expectError().getMessage should be("test err")
      s2.expectError().getMessage should be("test err")
    }

    "cancel upstream input when all outputs cancelled" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      sub1.cancel()

      completionProbe.expectMsg("onDownstreamFinish: TestRoute.out0")
      sub2.cancel()

      autoPublisher.expectCancellation()
    }

    "cancel upstream input when all outputs completed" in assertAllStagesStopped {
      val fixture = new TestFixture
      import fixture._

      sub1.request(1)
      s1.expectNext("onInput: a")
      sub2.request(1)
      s2.expectNext("onInput: b")

      sub1.request(2)
      sub2.request(2)
      autoPublisher.sendNext("finish")
      s1.expectComplete()
      s2.expectComplete()
      autoPublisher.expectCancellation()
    }

    "handle preStart and postStop" in assertAllStagesStopped {
      val p = TestProbe()

      FlowGraph.closed() { implicit b ⇒
        val r = b.add(new StartStopTestRoute(p.ref))

        Source(List("1", "2", "3")) ~> r.in
        r.out0 ~> Sink.ignore
        r.out1 ~> Sink.ignore
      }.run()

      p.expectMsg("preStart")
      p.expectMsg("1")
      p.expectMsg("2")
      p.expectMsg("3")
      p.expectMsg("postStop")
    }

    "invoke postStop after error" in assertAllStagesStopped {
      val p = TestProbe()

      FlowGraph.closed() { implicit b ⇒
        val r = b.add(new StartStopTestRoute(p.ref))

        Source(List("1", "fail", "2", "3")) ~> r.in
        r.out0 ~> Sink.ignore
        r.out1 ~> Sink.ignore
      }.run()

      p.expectMsg("preStart")
      p.expectMsg("1")
      p.expectMsg("fail")
      p.expectMsg("postStop")
    }

  }
}
