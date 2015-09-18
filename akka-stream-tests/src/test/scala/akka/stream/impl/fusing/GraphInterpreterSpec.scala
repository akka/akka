/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream._
import akka.stream.impl.fusing.GraphInterpreterSpec.TestSetup
import akka.stream.stage.{ InHandler, OutHandler, GraphStage, GraphStageLogic }
import akka.stream.testkit.AkkaSpec
import GraphInterpreter._

import scala.collection.immutable

class GraphInterpreterSpec extends AkkaSpec {
  import GraphInterpreterSpec._
  import GraphStages._

  "GraphInterpreter" must {

    // Reusable components
    val identity = new Identity[Int]
    val detacher = new Detacher[Int]
    val zip = new Zip[Int, String]
    val bcast = new Broadcast[Int](2)
    val merge = new Merge[Int](2)
    val balance = new Balance[Int](2)

    "implement identity" in new TestSetup {
      val source = UpstreamProbe[Int]("source")
      val sink = DownstreamProbe[Int]("sink")

      builder(identity)
        .connect(source, identity.in)
        .connect(identity.out, sink)
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, 1)))
    }

    "implement chained identity" in new TestSetup {
      val source = new UpstreamProbe[Int]("source")
      val sink = new DownstreamProbe[Int]("sink")

      // Constructing an assembly by hand and resolving ambiguities
      val assembly = GraphAssembly(
        stages = Array(identity, identity),
        ins = Array(identity.in, identity.in, null),
        inOwners = Array(0, 1, -1),
        outs = Array(null, identity.out, identity.out),
        outOwners = Array(-1, 0, 1))

      manualInit(assembly)
      interpreter.attachDownstreamBoundary(2, sink)
      interpreter.attachUpstreamBoundary(0, source)
      interpreter.init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, 1)))
    }

    "implement detacher stage" in new TestSetup {
      val source = UpstreamProbe[Int]("source")
      val sink = DownstreamProbe[Int]("sink")

      builder(detacher)
        .connect(source, detacher.in)
        .connect(detacher.out, sink)
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, 1), RequestOne(source)))

      // Source waits
      source.onNext(2)
      lastEvents() should ===(Set.empty)

      // "pushAndPull"
      sink.requestOne()
      lastEvents() should ===(Set(OnNext(sink, 2), RequestOne(source)))

      // Sink waits
      sink.requestOne()
      lastEvents() should ===(Set.empty)

      // "pushAndPull"
      source.onNext(3)
      lastEvents() should ===(Set(OnNext(sink, 3), RequestOne(source)))
    }

    "implement Zip" in new TestSetup {
      val source1 = new UpstreamProbe[Int]("source1")
      val source2 = new UpstreamProbe[String]("source2")
      val sink = new DownstreamProbe[(Int, String)]("sink")

      builder(zip)
        .connect(source1, zip.in0)
        .connect(source2, zip.in1)
        .connect(zip.out, sink)
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source1), RequestOne(source2)))

      source1.onNext(42)
      lastEvents() should ===(Set.empty)

      source2.onNext("Meaning of life")
      lastEvents() should ===(Set(OnNext(sink, (42, "Meaning of life"))))

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source1), RequestOne(source2)))
    }

    "implement Broadcast" in new TestSetup {
      val source = new UpstreamProbe[Int]("source")
      val sink1 = new DownstreamProbe[Int]("sink1")
      val sink2 = new DownstreamProbe[Int]("sink2")

      builder(bcast)
        .connect(source, bcast.in)
        .connect(bcast.out(0), sink1)
        .connect(bcast.out(1), sink2)
        .init()

      lastEvents() should ===(Set.empty)

      sink1.requestOne()
      lastEvents() should ===(Set.empty)

      sink2.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink1, 1), OnNext(sink2, 1)))

    }

    "implement broadcast-zip" in new TestSetup {
      val source = new UpstreamProbe[Int]("source")
      val sink = new DownstreamProbe[(Int, Int)]("sink")
      val zip = new Zip[Int, Int]

      builder(zip, bcast)
        .connect(source, bcast.in)
        .connect(bcast.out(0), zip.in0)
        .connect(bcast.out(1), zip.in1)
        .connect(zip.out, sink)
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, (1, 1))))

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(2)
      lastEvents() should ===(Set(OnNext(sink, (2, 2))))

    }

    "implement zip-broadcast" in new TestSetup {
      val source1 = new UpstreamProbe[Int]("source1")
      val source2 = new UpstreamProbe[Int]("source2")
      val sink1 = new DownstreamProbe[(Int, Int)]("sink")
      val sink2 = new DownstreamProbe[(Int, Int)]("sink2")
      val zip = new Zip[Int, Int]
      val bcast = new Broadcast[(Int, Int)](2)

      builder(bcast, zip)
        .connect(source1, zip.in0)
        .connect(source2, zip.in1)
        .connect(zip.out, bcast.in)
        .connect(bcast.out(0), sink1)
        .connect(bcast.out(1), sink2)
        .init()

      lastEvents() should ===(Set.empty)

      sink1.requestOne()
      lastEvents() should ===(Set.empty)

      sink2.requestOne()
      lastEvents() should ===(Set(RequestOne(source1), RequestOne(source2)))

      source1.onNext(1)
      lastEvents() should ===(Set.empty)

      source2.onNext(2)
      lastEvents() should ===(Set(OnNext(sink1, (1, 2)), OnNext(sink2, (1, 2))))

    }

    "implement merge" in new TestSetup {
      val source1 = new UpstreamProbe[Int]("source1")
      val source2 = new UpstreamProbe[Int]("source2")
      val sink = new DownstreamProbe[Int]("sink")

      builder(merge)
        .connect(source1, merge.in(0))
        .connect(source2, merge.in(1))
        .connect(merge.out, sink)
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source1), RequestOne(source2)))

      source1.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, 1), RequestOne(source1)))

      source2.onNext(2)
      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(OnNext(sink, 2), RequestOne(source2)))

      sink.requestOne()
      lastEvents() should ===(Set.empty)

      source2.onNext(3)
      lastEvents() should ===(Set(OnNext(sink, 3), RequestOne(source2)))

      sink.requestOne()
      lastEvents() should ===(Set.empty)

      source1.onNext(4)
      lastEvents() should ===(Set(OnNext(sink, 4), RequestOne(source1)))

    }

    "implement balance" in new TestSetup {
      val source = new UpstreamProbe[Int]("source")
      val sink1 = new DownstreamProbe[Int]("sink1")
      val sink2 = new DownstreamProbe[Int]("sink2")

      builder(balance)
        .connect(source, balance.in)
        .connect(balance.out(0), sink1)
        .connect(balance.out(1), sink2)
        .init()

      lastEvents() should ===(Set.empty)

      sink1.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      sink2.requestOne()
      lastEvents() should ===(Set.empty)

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink1, 1), RequestOne(source)))

      source.onNext(2)
      lastEvents() should ===(Set(OnNext(sink2, 2)))
    }

    "implement bidi-stage" in pending

    "implement non-divergent cycle" in new TestSetup {
      val source = new UpstreamProbe[Int]("source")
      val sink = new DownstreamProbe[Int]("sink")

      builder(merge, balance)
        .connect(source, merge.in(0))
        .connect(merge.out, balance.in)
        .connect(balance.out(0), sink)
        .connect(balance.out(1), merge.in(1))
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, 1), RequestOne(source)))

      // Token enters merge-balance cycle and gets stuck
      source.onNext(2)
      lastEvents() should ===(Set(RequestOne(source)))

      // Unstuck it
      sink.requestOne()
      lastEvents() should ===(Set(OnNext(sink, 2)))

    }

    "implement divergent cycle" in new TestSetup {
      val source = new UpstreamProbe[Int]("source")
      val sink = new DownstreamProbe[Int]("sink")

      builder(detacher, balance, merge)
        .connect(source, merge.in(0))
        .connect(merge.out, balance.in)
        .connect(balance.out(0), sink)
        .connect(balance.out(1), detacher.in)
        .connect(detacher.out, merge.in(1))
        .init()

      lastEvents() should ===(Set.empty)

      sink.requestOne()
      lastEvents() should ===(Set(RequestOne(source)))

      source.onNext(1)
      lastEvents() should ===(Set(OnNext(sink, 1), RequestOne(source)))

      // Token enters merge-balance cycle and spins until event limit
      // Without the limit this would spin forever (where forever = Int.MaxValue iterations)
      source.onNext(2, eventLimit = 1000)
      lastEvents() should ===(Set(RequestOne(source)))

      // The cycle is still alive and kicking, just suspended due to the event limit
      interpreter.isSuspended should be(true)

      // Do to the fairness properties of both the interpreter event queue and the balance stage
      // the element will eventually leave the cycle and reaches the sink.
      // This should not hang even though we do not have an event limit set
      sink.requestOne()
      lastEvents() should ===(Set(OnNext(sink, 2)))

      // The cycle is now empty
      interpreter.isSuspended should be(false)
    }
  }

}

object GraphInterpreterSpec {

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
    private var lastEvent: Set[TestEvent] = Set.empty
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

        _interpreter = new GraphInterpreter(assembly, NoMaterializer, (_, _, _) ⇒ ())

        for ((upstream, i) ← upstreams.zipWithIndex) {
          _interpreter.attachUpstreamBoundary(i, upstream._1)
        }

        for ((downstream, i) ← downstreams.zipWithIndex) {
          _interpreter.attachDownstreamBoundary(i + upstreams.size + connections.size, downstream._2)
        }

        _interpreter.init()
      }
    }

    def manualInit(assembly: GraphAssembly): Unit =
      _interpreter = new GraphInterpreter(assembly, NoMaterializer, (_, _, _) ⇒ ())

    def builder(stages: GraphStage[_ <: Shape]*): AssemblyBuilder = new AssemblyBuilder(stages.toSeq)

    def lastEvents(): Set[TestEvent] = {
      val result = lastEvent
      lastEvent = Set.empty
      result
    }

    case class UpstreamProbe[T](override val toString: String) extends UpstreamBoundaryStageLogic[T] {
      val out = Outlet[T]("out")

      setHandler(out, new OutHandler {
        override def onPull(): Unit = lastEvent += RequestOne(UpstreamProbe.this)
      })

      def onNext(elem: T, eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- NEXT: $this $elem")
        push(out, elem)
        interpreter.execute(eventLimit)
      }
    }

    case class DownstreamProbe[T](override val toString: String) extends DownstreamBoundaryStageLogic[T] {
      val in = Inlet[T]("in")

      setHandler(in, new InHandler {
        override def onPush(): Unit = lastEvent += OnNext(DownstreamProbe.this, grab(in))
      })

      def requestOne(eventLimit: Int = Int.MaxValue): Unit = {
        if (GraphInterpreter.Debug) println(s"----- REQ $this")
        pull(in)
        interpreter.execute(eventLimit)
      }
    }

  }
}
