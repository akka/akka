/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.event.Logging
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreter.{ DownstreamBoundaryStageLogic, Failed, GraphAssembly, UpstreamBoundaryStageLogic }
import akka.stream.stage.AbstractStage.PushPullGraphStage
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, _ }
import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils.TE
import akka.stream.impl.fusing.GraphInterpreter.GraphAssembly

trait GraphInterpreterSpecKit extends AkkaSpec {

  val logger = Logging(system, "InterpreterSpecKit")

  abstract class Builder {
    private var _interpreter: GraphInterpreter = _
    protected def interpreter: GraphInterpreter = _interpreter

    def stepAll(): Unit = interpreter.execute(eventLimit = Int.MaxValue)
    def step(): Unit = interpreter.execute(eventLimit = 1)

    object Upstream extends UpstreamBoundaryStageLogic[Int] {
      override val out = Outlet[Int]("up")
      out.id = 0
    }

    object Downstream extends DownstreamBoundaryStageLogic[Int] {
      override val in = Inlet[Int]("down")
      in.id = 0
    }

    class AssemblyBuilder(stages: Seq[GraphStageWithMaterializedValue[_ <: Shape, _]]) {
      var upstreams = Vector.empty[(UpstreamBoundaryStageLogic[_], Inlet[_])]
      var downstreams = Vector.empty[(Outlet[_], DownstreamBoundaryStageLogic[_])]
      var connections = Vector.empty[(Outlet[_], Inlet[_])]

      def connect[T](upstream: UpstreamBoundaryStageLogic[T], in: Inlet[T]): AssemblyBuilder = {
        upstreams :+= upstream -> in
        this
      }

      def connect[T](out: Outlet[T], downstream: DownstreamBoundaryStageLogic[T]): AssemblyBuilder = {
        downstreams :+= out -> downstream
        this
      }

      def connect[T](out: Outlet[T], in: Inlet[T]): AssemblyBuilder = {
        connections :+= out -> in
        this
      }

      def buildAssembly(): GraphAssembly = {
        val ins = upstreams.map(_._2) ++ connections.map(_._2)
        val outs = connections.map(_._1) ++ downstreams.map(_._1)
        val inOwners = ins.map { in ⇒ stages.indexWhere(_.shape.inlets.contains(in)) }
        val outOwners = outs.map { out ⇒ stages.indexWhere(_.shape.outlets.contains(out)) }

        new GraphAssembly(
          stages.toArray,
          Array.fill(stages.size)(Attributes.none),
          (ins ++ Vector.fill(downstreams.size)(null)).toArray,
          (inOwners ++ Vector.fill(downstreams.size)(-1)).toArray,
          (Vector.fill(upstreams.size)(null) ++ outs).toArray,
          (Vector.fill(upstreams.size)(-1) ++ outOwners).toArray)
      }

      def init(): Unit = {
        val assembly = buildAssembly()

        val (inHandlers, outHandlers, logics) =
          assembly.materialize(Attributes.none, assembly.stages.map(_.module), new java.util.HashMap, _ ⇒ ())
        _interpreter = new GraphInterpreter(assembly, NoMaterializer, logger, inHandlers, outHandlers, logics,
          (_, _, _) ⇒ (), fuzzingMode = false, null)

        for ((upstream, i) ← upstreams.zipWithIndex) {
          _interpreter.attachUpstreamBoundary(i, upstream._1)
        }

        for ((downstream, i) ← downstreams.zipWithIndex) {
          _interpreter.attachDownstreamBoundary(i + upstreams.size + connections.size, downstream._2)
        }

        _interpreter.init(null)
      }
    }

    def manualInit(assembly: GraphAssembly): Unit = {
      val (inHandlers, outHandlers, logics) =
        assembly.materialize(Attributes.none, assembly.stages.map(_.module), new java.util.HashMap, _ ⇒ ())
      _interpreter = new GraphInterpreter(assembly, NoMaterializer, logger, inHandlers, outHandlers, logics,
        (_, _, _) ⇒ (), fuzzingMode = false, null)
    }

    def builder(stages: GraphStageWithMaterializedValue[_ <: Shape, _]*): AssemblyBuilder = new AssemblyBuilder(stages)
  }

  abstract class TestSetup extends Builder {

    sealed trait TestEvent {
      def source: GraphStageLogic
    }

    case class OnComplete(source: GraphStageLogic) extends TestEvent
    case class Cancel(source: GraphStageLogic) extends TestEvent
    case class OnError(source: GraphStageLogic, cause: Throwable) extends TestEvent
    case class OnNext(source: GraphStageLogic, elem: Any) extends TestEvent
    case class RequestOne(source: GraphStageLogic) extends TestEvent
    case class RequestAnother(source: GraphStageLogic) extends TestEvent
    case class PreStart(source: GraphStageLogic) extends TestEvent
    case class PostStop(source: GraphStageLogic) extends TestEvent

    protected var lastEvent: Set[TestEvent] = Set.empty

    def lastEvents(): Set[TestEvent] = {
      val result = lastEvent
      clearEvents()
      result
    }

    def clearEvents(): Unit = lastEvent = Set.empty

    class UpstreamProbe[T](override val toString: String) extends UpstreamBoundaryStageLogic[T] {
      val out = Outlet[T]("out")
      out.id = 0

      setHandler(out, new OutHandler {
        override def onPull(): Unit = lastEvent += RequestOne(UpstreamProbe.this)
        override def onDownstreamFinish(): Unit = lastEvent += Cancel(UpstreamProbe.this)
      })

      def onNext(elem: T, eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- NEXT: $this $elem")
        push(out, elem)
        interpreter.execute(eventLimit)
      }

      def onComplete(eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- COMPLETE $this")
        complete(out)
        interpreter.execute(eventLimit)
      }

      def onFailure(eventLimit: Int = Int.MaxValue, ex: Throwable): Unit = {
        if (GraphInterpreter.Debug) println(s"----- FAIL $this")
        fail(out, ex)
        interpreter.execute(eventLimit)
      }
    }

    class DownstreamProbe[T](override val toString: String) extends DownstreamBoundaryStageLogic[T] {
      val in = Inlet[T]("in")
      in.id = 0

      setHandler(in, new InHandler {
        override def onPush(): Unit = lastEvent += OnNext(DownstreamProbe.this, grab(in))
        override def onUpstreamFinish(): Unit = lastEvent += OnComplete(DownstreamProbe.this)
        override def onUpstreamFailure(ex: Throwable): Unit = lastEvent += OnError(DownstreamProbe.this, ex)
      })

      def requestOne(eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- REQ $this")
        pull(in)
        interpreter.execute(eventLimit)
      }

      def cancel(eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- CANCEL $this")
        cancel(in)
        interpreter.execute(eventLimit)
      }
    }

  }

  abstract class PortTestSetup extends TestSetup {
    val out = new UpstreamPortProbe[Int]
    val in = new DownstreamPortProbe[Int]

    class UpstreamPortProbe[T] extends UpstreamProbe[T]("upstreamPort") {
      def isAvailable: Boolean = isAvailable(out)
      def isClosed: Boolean = isClosed(out)

      def push(elem: T): Unit = push(out, elem)
      def complete(): Unit = complete(out)
      def fail(ex: Throwable): Unit = fail(out, ex)
    }

    class DownstreamPortProbe[T] extends DownstreamProbe[T]("upstreamPort") {
      def isAvailable: Boolean = isAvailable(in)
      def hasBeenPulled: Boolean = hasBeenPulled(in)
      def isClosed: Boolean = isClosed(in)

      def pull(): Unit = pull(in)
      def cancel(): Unit = cancel(in)
      def grab(): T = grab(in)

      setHandler(in, new InHandler {

        // Modified onPush that does not grab() automatically the element. This accesses some internals.
        override def onPush(): Unit = {
          val internalEvent = interpreter.connectionSlots(portToConn(in.id))

          internalEvent match {
            case Failed(_, elem) ⇒ lastEvent += OnNext(DownstreamPortProbe.this, elem)
            case elem            ⇒ lastEvent += OnNext(DownstreamPortProbe.this, elem)
          }
        }

        override def onUpstreamFinish() = lastEvent += OnComplete(DownstreamPortProbe.this)
        override def onUpstreamFailure(ex: Throwable) = lastEvent += OnError(DownstreamPortProbe.this, ex)
      })
    }

    private val assembly = new GraphAssembly(
      stages = Array.empty,
      originalAttributes = Array.empty,
      ins = Array(null),
      inOwners = Array(-1),
      outs = Array(null),
      outOwners = Array(-1))

    manualInit(assembly)
    interpreter.attachDownstreamBoundary(0, in)
    interpreter.attachUpstreamBoundary(0, out)
    interpreter.init(null)
  }

  abstract class FailingStageSetup(initFailOnNextEvent: Boolean = false) extends TestSetup {

    val upstream = new UpstreamPortProbe[Int]
    val downstream = new DownstreamPortProbe[Int]

    private var _failOnNextEvent: Boolean = initFailOnNextEvent
    private var _failOnPostStop: Boolean = false

    def failOnNextEvent(): Unit = _failOnNextEvent = true
    def failOnPostStop(): Unit = _failOnPostStop = true

    def testException = TE("test")

    private val stagein = Inlet[Int]("sandwitch.in")
    private val stageout = Outlet[Int]("sandwitch.out")
    private val stageshape = FlowShape(stagein, stageout)

    // Must be lazy because I turned this stage "inside-out" therefore changing initialization order
    // to make tests a bit more readable
    lazy val stage: GraphStageLogic = new GraphStageLogic(stageshape) {
      private def mayFail(task: ⇒ Unit): Unit = {
        if (!_failOnNextEvent) task
        else {
          _failOnNextEvent = false
          throw testException
        }
      }

      setHandler(stagein, new InHandler {
        override def onPush(): Unit = mayFail(push(stageout, grab(stagein)))
        override def onUpstreamFinish(): Unit = mayFail(completeStage())
        override def onUpstreamFailure(ex: Throwable): Unit = mayFail(failStage(ex))
      })

      setHandler(stageout, new OutHandler {
        override def onPull(): Unit = mayFail(pull(stagein))
        override def onDownstreamFinish(): Unit = mayFail(completeStage())
      })

      override def preStart(): Unit = mayFail(lastEvent += PreStart(stage))
      override def postStop(): Unit =
        if (!_failOnPostStop) lastEvent += PostStop(stage)
        else throw testException

      override def toString = "stage"
    }

    private val sandwitchStage = new GraphStage[FlowShape[Int, Int]] {
      override def shape = stageshape
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = stage
    }

    class UpstreamPortProbe[T] extends UpstreamProbe[T]("upstreamPort") {
      def push(elem: T): Unit = push(out, elem)
      def complete(): Unit = complete(out)
      def fail(ex: Throwable): Unit = fail(out, ex)
    }

    class DownstreamPortProbe[T] extends DownstreamProbe[T]("upstreamPort") {
      def pull(): Unit = pull(in)
      def cancel(): Unit = cancel(in)
    }

    builder(sandwitchStage)
      .connect(upstream, stagein)
      .connect(stageout, downstream)
      .init()
  }

  implicit class ToGraphStage[I, O](stage: Stage[I, O]) {
    def toGS: PushPullGraphStage[Any, Any, Any] = {
      val s = stage
      new PushPullGraphStage[Any, Any, Any](
        (_) ⇒ s.asInstanceOf[Stage[Any, Any]],
        Attributes.none)
    }
  }

  abstract class OneBoundedSetup[T](_ops: GraphStageWithMaterializedValue[Shape, Any]*) extends Builder {
    val ops = _ops.toArray

    def this(op: Seq[Stage[_, _]], dummy: Int = 42) = {
      this(op.map(_.toGS): _*)
    }

    val upstream = new UpstreamOneBoundedProbe[T]
    val downstream = new DownstreamOneBoundedPortProbe[T]
    var lastEvent = Set.empty[TestEvent]

    sealed trait TestEvent

    case object OnComplete extends TestEvent
    case object Cancel extends TestEvent
    case class OnError(cause: Throwable) extends TestEvent
    case class OnNext(elem: Any) extends TestEvent
    case object RequestOne extends TestEvent
    case object RequestAnother extends TestEvent

    private def run() = interpreter.execute(Int.MaxValue)

    private def initialize(): Unit = {
      import GraphInterpreter.Boundary

      var i = 0
      val attributes = Array.fill[Attributes](ops.length)(Attributes.none)
      val ins = Array.ofDim[Inlet[_]](ops.length + 1)
      val inOwners = Array.ofDim[Int](ops.length + 1)
      val outs = Array.ofDim[Outlet[_]](ops.length + 1)
      val outOwners = Array.ofDim[Int](ops.length + 1)

      ins(ops.length) = null
      inOwners(ops.length) = Boundary
      outs(0) = null
      outOwners(0) = Boundary

      while (i < ops.length) {
        val stage = ops(i).asInstanceOf[GraphStageWithMaterializedValue[FlowShape[_, _], _]]
        ins(i) = stage.shape.in
        inOwners(i) = i
        outs(i + 1) = stage.shape.out
        outOwners(i + 1) = i
        i += 1
      }

      manualInit(new GraphAssembly(ops, attributes, ins, inOwners, outs, outOwners))
      interpreter.attachUpstreamBoundary(0, upstream)
      interpreter.attachDownstreamBoundary(ops.length, downstream)

      interpreter.init(null)

    }

    initialize()
    run() // Detached stages need the prefetch

    def lastEvents(): Set[TestEvent] = {
      val events = lastEvent
      lastEvent = Set.empty
      events
    }

    class UpstreamOneBoundedProbe[TT] extends UpstreamBoundaryStageLogic[TT] {
      val out = Outlet[TT]("out")
      out.id = 0

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (lastEvent.contains(RequestOne)) lastEvent += RequestAnother
          else lastEvent += RequestOne
        }

        override def onDownstreamFinish(): Unit = lastEvent += Cancel
      })

      def onNext(elem: TT): Unit = {
        push(out, elem)
        run()
      }
      def onComplete(): Unit = {
        complete(out)
        run()
      }

      def onNextAndComplete(elem: TT): Unit = {
        push(out, elem)
        complete(out)
        run()
      }

      def onError(ex: Throwable): Unit = {
        fail(out, ex)
        run()
      }
    }

    class DownstreamOneBoundedPortProbe[TT] extends DownstreamBoundaryStageLogic[TT] {
      val in = Inlet[TT]("in")
      in.id = 0

      setHandler(in, new InHandler {

        // Modified onPush that does not grab() automatically the element. This accesses some internals.
        override def onPush(): Unit = {
          lastEvent += OnNext(grab(in))
        }

        override def onUpstreamFinish() = lastEvent += OnComplete
        override def onUpstreamFailure(ex: Throwable) = lastEvent += OnError(ex)
      })

      def requestOne(): Unit = {
        pull(in)
        run()
      }

      def cancel(): Unit = {
        cancel(in)
        run()
      }

    }

  }

}
