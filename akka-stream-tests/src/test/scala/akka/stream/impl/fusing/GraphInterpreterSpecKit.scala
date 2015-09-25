/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.event.Logging
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreter.{ Failed, GraphAssembly, DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic }
import akka.stream.stage.{ InHandler, OutHandler, GraphStage, GraphStageLogic }
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils.TE

trait GraphInterpreterSpecKit extends AkkaSpec {

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

  abstract class TestSetup {
    protected var lastEvent: Set[TestEvent] = Set.empty
    private var _interpreter: GraphInterpreter = _
    protected def interpreter: GraphInterpreter = _interpreter

    def stepAll(): Unit = interpreter.execute(eventLimit = Int.MaxValue)
    def step(): Unit = interpreter.execute(eventLimit = 1)

    class AssemblyBuilder(stages: Seq[GraphStage[_ <: Shape]]) {
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

      def init(): Unit = {
        val ins = upstreams.map(_._2) ++ connections.map(_._2)
        val outs = connections.map(_._1) ++ downstreams.map(_._1)
        val inOwners = ins.map { in ⇒ stages.indexWhere(_.shape.inlets.contains(in)) }
        val outOwners = outs.map { out ⇒ stages.indexWhere(_.shape.outlets.contains(out)) }

        val assembly = GraphAssembly(
          stages.toArray,
          (ins ++ Vector.fill(downstreams.size)(null)).toArray,
          (inOwners ++ Vector.fill(downstreams.size)(-1)).toArray,
          (Vector.fill(upstreams.size)(null) ++ outs).toArray,
          (Vector.fill(upstreams.size)(-1) ++ outOwners).toArray)

        val (inHandlers, outHandlers, logics, _) = assembly.materialize()
        _interpreter = new GraphInterpreter(assembly, NoMaterializer, Logging(system, classOf[TestSetup]), inHandlers, outHandlers, logics, (_, _, _) ⇒ ())

        for ((upstream, i) ← upstreams.zipWithIndex) {
          _interpreter.attachUpstreamBoundary(i, upstream._1)
        }

        for ((downstream, i) ← downstreams.zipWithIndex) {
          _interpreter.attachDownstreamBoundary(i + upstreams.size + connections.size, downstream._2)
        }

        _interpreter.init()
      }
    }

    def manualInit(assembly: GraphAssembly): Unit = {
      val (inHandlers, outHandlers, logics, _) = assembly.materialize()
      _interpreter = new GraphInterpreter(assembly, NoMaterializer, Logging(system, classOf[TestSetup]), inHandlers, outHandlers, logics, (_, _, _) ⇒ ())
    }

    def builder(stages: GraphStage[_ <: Shape]*): AssemblyBuilder = new AssemblyBuilder(stages.toSeq)

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
          val internalEvent = interpreter.connectionSlots(inToConn(in.id))

          internalEvent match {
            case Failed(_, elem) ⇒ lastEvent += OnNext(DownstreamPortProbe.this, elem)
            case elem            ⇒ lastEvent += OnNext(DownstreamPortProbe.this, elem)
          }
        }

        override def onUpstreamFinish() = lastEvent += OnComplete(DownstreamPortProbe.this)
        override def onUpstreamFailure(ex: Throwable) = lastEvent += OnError(DownstreamPortProbe.this, ex)
      })
    }

    private val assembly = GraphAssembly(
      stages = Array.empty,
      ins = Array(null),
      inOwners = Array(-1),
      outs = Array(null),
      outOwners = Array(-1))

    manualInit(assembly)
    interpreter.attachDownstreamBoundary(0, in)
    interpreter.attachUpstreamBoundary(0, out)
    interpreter.init()
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
      override def createLogic: GraphStageLogic = stage
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

}
