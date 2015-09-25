/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.{ NoMaterializer, Outlet, Inlet, Shape }
import akka.stream.impl.fusing.GraphInterpreter.{ GraphAssembly, DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic }
import akka.stream.stage.{ InHandler, OutHandler, GraphStage, GraphStageLogic }

trait GraphInterpreterSpecKit {

  sealed trait TestEvent {
    def source: GraphStageLogic
  }

  case class OnComplete(source: GraphStageLogic) extends TestEvent
  case class Cancel(source: GraphStageLogic) extends TestEvent
  case class OnError(source: GraphStageLogic, cause: Throwable) extends TestEvent
  case class OnNext(source: GraphStageLogic, elem: Any) extends TestEvent
  case class RequestOne(source: GraphStageLogic) extends TestEvent
  case class RequestAnother(source: GraphStageLogic) extends TestEvent

  abstract class TestSetup {
    protected var lastEvent: Set[TestEvent] = Set.empty
    private var _interpreter: GraphInterpreter = _
    protected def interpreter: GraphInterpreter = _interpreter

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
        _interpreter = new GraphInterpreter(assembly, NoMaterializer, inHandlers, outHandlers, logics, (_, _, _) ⇒ ())

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
      _interpreter = new GraphInterpreter(assembly, NoMaterializer, inHandlers, outHandlers, logics, (_, _, _) ⇒ ())
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

      setHandler(out, new OutHandler {
        override def onPull(): Unit = lastEvent += RequestOne(UpstreamProbe.this)
        override def onDownstreamFinish() = lastEvent += Cancel(UpstreamProbe.this)
      })

      def onNext(elem: T, eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- NEXT: $this $elem")
        push(out, elem)
        interpreter.execute(eventLimit)
      }
    }

    class DownstreamProbe[T](override val toString: String) extends DownstreamBoundaryStageLogic[T] {
      val in = Inlet[T]("in")

      setHandler(in, new InHandler {
        override def onPush(): Unit = lastEvent += OnNext(DownstreamProbe.this, grab(in))
        override def onUpstreamFinish() = lastEvent += OnComplete(DownstreamProbe.this)
        override def onUpstreamFailure(ex: Throwable) = OnError(DownstreamProbe.this, ex)
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
        override def onPush(): Unit =
          lastEvent +=
            OnNext(
              DownstreamPortProbe.this,
              interpreter.connectionStates(inToConn(in)))

        override def onUpstreamFinish() = lastEvent += OnComplete(DownstreamPortProbe.this)
        override def onUpstreamFailure(ex: Throwable) = OnError(DownstreamPortProbe.this, ex)
      })
    }

    def stepAll(): Unit = interpreter.execute(eventLimit = Int.MaxValue)
    def step(): Unit = interpreter.execute(eventLimit = 1)

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

}
