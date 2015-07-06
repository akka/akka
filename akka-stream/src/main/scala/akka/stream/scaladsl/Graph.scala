/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Junctions._
import akka.stream.impl.GenJunctions._
import akka.stream.impl.Stages.{ MaterializingStageFactory, StageModule }
import akka.stream.impl._
import akka.stream.impl.StreamLayout._
import akka.stream._
import Attributes.name
import scala.collection.immutable
import scala.annotation.unchecked.uncheckedVariance
import scala.annotation.tailrec

object Merge {
  /**
   * Create a new `Merge` with the specified number of input ports.
   *
   * @param inputPorts number of input ports
   */
  def apply[T](inputPorts: Int): Merge[T] = {
    val shape = new UniformFanInShape[T, T](inputPorts)
    new Merge(inputPorts, shape, new MergeModule(shape, Attributes.name("Merge")))
  }

}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * '''Emits when''' one of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete
 *
 * '''Cancels when''' downstream cancels
 */
class Merge[T] private (inputPorts: Int,
                        override val shape: UniformFanInShape[T, T],
                        private[stream] override val module: StreamLayout.Module)
  extends Graph[UniformFanInShape[T, T], Unit] {

  override def withAttributes(attr: Attributes): Merge[T] =
    new Merge(inputPorts, shape, module.withAttributes(attr).nest())

  override def named(name: String): Merge[T] = withAttributes(Attributes.name(name))
}

object MergePreferred {
  import FanInShape._
  final class MergePreferredShape[T](val secondaryPorts: Int, _init: Init[T]) extends UniformFanInShape[T, T](secondaryPorts, _init) {
    def this(secondaryPorts: Int, name: String) = this(secondaryPorts, Name(name))
    override protected def construct(init: Init[T]): FanInShape[T] = new MergePreferredShape(secondaryPorts, init)
    override def deepCopy(): MergePreferredShape[T] = super.deepCopy().asInstanceOf[MergePreferredShape[T]]

    val preferred = newInlet[T]("preferred")
  }

  /**
   * Create a new `MergePreferred` with the specified number of secondary input ports.
   *
   * @param secondaryPorts number of secondary input ports
   */
  def apply[T](secondaryPorts: Int): MergePreferred[T] = {
    val shape = new MergePreferredShape[T](secondaryPorts, "MergePreferred")
    new MergePreferred(secondaryPorts, shape, new MergePreferredModule(shape, Attributes.name("MergePreferred")))
  }
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * A `MergePreferred` has one `out` port, one `preferred` input port and 0 or more secondary `in` ports.
 *
 * '''Emits when''' one of the inputs has an element available, preferring
 * a specified input if multiple have elements available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete
 *
 * '''Cancels when''' downstream cancels
 *
 * A `Broadcast` has one `in` port and 2 or more `out` ports.
 */
class MergePreferred[T] private (secondaryPorts: Int,
                                 override val shape: MergePreferred.MergePreferredShape[T],
                                 private[stream] override val module: StreamLayout.Module)
  extends Graph[MergePreferred.MergePreferredShape[T], Unit] {

  override def withAttributes(attr: Attributes): MergePreferred[T] =
    new MergePreferred(secondaryPorts, shape, module.withAttributes(attr).nest())

  override def named(name: String): MergePreferred[T] = withAttributes(Attributes.name(name))
}

object Broadcast {
  /**
   * Create a new `Broadcast` with the specified number of output ports.
   *
   * @param outputPorts number of output ports
   * @param eagerCancel if true, broadcast cancels upstream if any of its downstreams cancel.
   */
  def apply[T](outputPorts: Int, eagerCancel: Boolean = false): Broadcast[T] = {
    val shape = new UniformFanOutShape[T, T](outputPorts)
    new Broadcast(outputPorts, shape, new BroadcastModule(shape, eagerCancel, Attributes.name("Broadcast")))
  }
}

/**
 * Fan-out the stream to several streams emitting each incoming upstream element to all downstream consumers.
 * It will not shut down until the subscriptions for at least two downstream subscribers have been established.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when'''
 *   If eagerCancel is enabled: when any downstream cancels; otherwise: when all downstreams cancel
 *
 */
class Broadcast[T] private (outputPorts: Int,
                            override val shape: UniformFanOutShape[T, T],
                            private[stream] override val module: StreamLayout.Module)
  extends Graph[UniformFanOutShape[T, T], Unit] {

  override def withAttributes(attr: Attributes): Broadcast[T] =
    new Broadcast(outputPorts, shape, module.withAttributes(attr).nest())

  override def named(name: String): Broadcast[T] = withAttributes(Attributes.name(name))
}

object Balance {
  /**
   * Create a new `Balance` with the specified number of output ports.
   *
   * @param outputPorts number of output ports
   * @param waitForAllDownstreams if you use `waitForAllDownstreams = true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element,
   *   default value is `false`
   */
  def apply[T](outputPorts: Int, waitForAllDownstreams: Boolean = false): Balance[T] = {
    val shape = new UniformFanOutShape[T, T](outputPorts)
    new Balance(outputPorts, waitForAllDownstreams, shape,
      new BalanceModule(shape, waitForAllDownstreams, Attributes.name("Balance")))
  }
}

/**
 * Fan-out the stream to several streams. Each upstream element is emitted to the first available downstream consumer.
 * It will not shut down until the subscriptions
 * for at least two downstream subscribers have been established.
 *
 * A `Balance` has one `in` port and 2 or more `out` ports.
 *
 * '''Emits when''' any of the outputs stops backpressuring; emits the element to the first available output
 *
 * '''Backpressures when''' all of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' all downstreams cancel
 */
class Balance[T] private (outputPorts: Int,
                          waitForAllDownstreams: Boolean,
                          override val shape: UniformFanOutShape[T, T],
                          private[stream] override val module: StreamLayout.Module)
  extends Graph[UniformFanOutShape[T, T], Unit] {

  override def withAttributes(attr: Attributes): Balance[T] =
    new Balance(outputPorts, waitForAllDownstreams, shape, module.withAttributes(attr).nest())

  override def named(name: String): Balance[T] = withAttributes(Attributes.name(name))
}

object Zip {
  /**
   * Create a new `Zip`.
   */
  def apply[A, B](): Zip[A, B] = {
    val shape = new FanInShape2[A, B, (A, B)]("Zip")
    new Zip(shape, new ZipWith2Module[A, B, (A, B)](shape, Keep.both, Attributes.name("Zip")))
  }
}

/**
 * Combine the elements of 2 streams into a stream of tuples.
 *
 * A `Zip` has a `left` and a `right` input port and one `out` port
 *
 * '''Emits when''' all of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' any upstream completes
 *
 * '''Cancels when''' downstream cancels
 */
class Zip[A, B] private (override val shape: FanInShape2[A, B, (A, B)],
                         private[stream] override val module: StreamLayout.Module)
  extends Graph[FanInShape2[A, B, (A, B)], Unit] {

  override def withAttributes(attr: Attributes): Zip[A, B] =
    new Zip(shape, module.withAttributes(attr).nest())

  override def named(name: String): Zip[A, B] = withAttributes(Attributes.name(name))
}

/**
 * Combine the elements of multiple streams into a stream of combined elements using a combiner function.
 *
 * '''Emits when''' all of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' any upstream completes
 *
 * '''Cancels when''' downstream cancels
 */
object ZipWith extends ZipWithApply

/**
 * Takes a stream of pair elements and splits each pair to two output streams.
 *
 * An `Unzip` has one `in` port and one `left` and one `right` output port.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressures
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' any downstream cancels
 */
object Unzip {
  /**
   * Create a new `Unzip`.
   */
  def apply[A, B](): Unzip[A, B] = {
    val shape = new FanOutShape2[(A, B), A, B]("Unzip")
    new Unzip(shape, new UnzipModule(shape, Attributes.name("Unzip")))
  }
}

/**
 * Combine the elements of multiple streams into a stream of the combined elements.
 */
class Unzip[A, B] private (override val shape: FanOutShape2[(A, B), A, B],
                           private[stream] override val module: StreamLayout.Module)
  extends Graph[FanOutShape2[(A, B), A, B], Unit] {

  override def withAttributes(attr: Attributes): Unzip[A, B] =
    new Unzip(shape, module.withAttributes(attr).nest())

  override def named(name: String): Unzip[A, B] = withAttributes(Attributes.name(name))
}

object Concat {
  /**
   * Create a new `Concat`.
   */
  def apply[T](): Concat[T] = {
    val shape = new UniformFanInShape[T, T](2)
    new Concat(shape, new ConcatModule(shape, Attributes.name("Concat")))
  }
}

/**
 * Takes two streams and outputs one stream formed from the two input streams
 * by first emitting all of the elements from the first stream and then emitting
 * all of the elements from the second stream.
 *
 * A `Concat` has one `first` port, one `second` port and one `out` port.
 *
 * '''Emits when''' the current stream has an element available; if the current input completes, it tries the next one
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete
 *
 * '''Cancels when''' downstream cancels
 */
class Concat[T] private (override val shape: UniformFanInShape[T, T],
                         private[stream] override val module: StreamLayout.Module)
  extends Graph[UniformFanInShape[T, T], Unit] {

  override def withAttributes(attr: Attributes): Concat[T] =
    new Concat(shape, module.withAttributes(attr).nest())

  override def named(name: String): Concat[T] = withAttributes(Attributes.name(name))
}

object FlowGraph extends GraphApply {

  class Builder[+M] private[stream] () {
    private var moduleInProgress: Module = EmptyModule

    def addEdge[A, B, M2](from: Outlet[A], via: Graph[FlowShape[A, B], M2], to: Inlet[B]): Unit = {
      val flowCopy = via.module.carbonCopy
      moduleInProgress =
        moduleInProgress
          .compose(flowCopy)
          .wire(from, flowCopy.shape.inlets.head)
          .wire(flowCopy.shape.outlets.head, to)
    }

    def addEdge[T](from: Outlet[T], to: Inlet[T]): Unit = {
      moduleInProgress = moduleInProgress.wire(from, to)
    }

    /**
     * Import a graph into this module, performing a deep copy, discarding its
     * materialized value and returning the copied Ports that are now to be
     * connected.
     */
    def add[S <: Shape](graph: Graph[S, _]): S = {
      if (StreamLayout.Debug) StreamLayout.validate(graph.module)
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.compose(copy)
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    /**
     * INTERNAL API.
     *
     * This is only used by the materialization-importing apply methods of Source,
     * Flow, Sink and Graph.
     */
    private[stream] def add[S <: Shape, A](graph: Graph[S, _], transform: (A) ⇒ Any): S = {
      if (StreamLayout.Debug) StreamLayout.validate(graph.module)
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.compose(copy.transformMaterializedValue(transform.asInstanceOf[Any ⇒ Any]))
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    /**
     * INTERNAL API.
     *
     * This is only used by the materialization-importing apply methods of Source,
     * Flow, Sink and Graph.
     */
    private[stream] def add[S <: Shape, A, B](graph: Graph[S, _], combine: (A, B) ⇒ Any): S = {
      if (StreamLayout.Debug) StreamLayout.validate(graph.module)
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.compose(copy, combine)
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    def add[T](s: Source[T, _]): Outlet[T] = add(s: Graph[SourceShape[T], _]).outlet
    def add[T](s: Sink[T, _]): Inlet[T] = add(s: Graph[SinkShape[T], _]).inlet

    /**
     * Returns an [[Outlet]] that gives access to the materialized value of this graph. Once the graph is materialized
     * this outlet will emit exactly one element which is the materialized value. It is possible to expose this
     * outlet as an externally accessible outlet of a [[Source]], [[Sink]], [[Flow]] or [[BidiFlow]].
     *
     * It is possible to call this method multiple times to get multiple [[Outlet]] instances if necessary. All of
     * the outlets will emit the materialized value.
     *
     * Be careful to not to feed the result of this outlet to a stage that produces the materialized value itself (for
     * example to a [[Sink#fold]] that contributes to the materialized value) since that might lead to an unresolvable
     * dependency cycle.
     *
     * @return The outlet that will emit the materialized value.
     */
    def materializedValue: Outlet[M] = {
      val module = new MaterializedValueSource[Any]
      moduleInProgress = moduleInProgress.compose(module)
      module.shape.outlet.asInstanceOf[Outlet[M]]
    }

    private[stream] def andThen(port: OutPort, op: StageModule): Unit = {
      moduleInProgress =
        moduleInProgress
          .compose(op)
          .wire(port, op.inPort)
    }

    private[stream] def buildRunnable[Mat](): RunnableGraph[Mat] = {
      if (!moduleInProgress.isRunnable) {
        throw new IllegalArgumentException(
          "Cannot build the RunnableGraph because there are unconnected ports: " +
            (moduleInProgress.outPorts ++ moduleInProgress.inPorts).mkString(", "))
      }
      new RunnableGraph(moduleInProgress.nest())
    }

    private[stream] def buildSource[T, Mat](outlet: Outlet[T]): Source[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalArgumentException("Cannot build the Source since no ports remain open")
      if (!moduleInProgress.isSource)
        throw new IllegalArgumentException(
          s"Cannot build Source with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalArgumentException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      new Source(moduleInProgress.replaceShape(SourceShape(outlet)).nest())
    }

    private[stream] def buildFlow[In, Out, Mat](inlet: Inlet[In], outlet: Outlet[Out]): Flow[In, Out, Mat] = {
      if (!moduleInProgress.isFlow)
        throw new IllegalArgumentException(
          s"Cannot build Flow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalArgumentException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalArgumentException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Flow(moduleInProgress.replaceShape(FlowShape(inlet, outlet)).nest())
    }

    private[stream] def buildBidiFlow[I1, O1, I2, O2, Mat](shape: BidiShape[I1, O1, I2, O2]): BidiFlow[I1, O1, I2, O2, Mat] = {
      if (!moduleInProgress.isBidiFlow)
        throw new IllegalArgumentException(
          s"Cannot build BidiFlow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.toSet != shape.outlets.toSet)
        throw new IllegalArgumentException(s"provided Outlets [${shape.outlets.mkString(",")}] does not equal the module’s open Outlets [${moduleInProgress.outPorts.mkString(",")}]")
      if (moduleInProgress.inPorts.toSet != shape.inlets.toSet)
        throw new IllegalArgumentException(s"provided Inlets [${shape.inlets.mkString(",")}] does not equal the module’s open Inlets [${moduleInProgress.inPorts.mkString(",")}]")
      new BidiFlow(moduleInProgress.replaceShape(shape).nest())
    }

    private[stream] def buildSink[T, Mat](inlet: Inlet[T]): Sink[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalArgumentException("Cannot build the Sink since no ports remain open")
      if (!moduleInProgress.isSink)
        throw new IllegalArgumentException(
          s"Cannot build Sink with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalArgumentException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Sink(moduleInProgress.replaceShape(SinkShape(inlet)).nest())
    }

    private[stream] def module: Module = moduleInProgress

    /** Converts this Scala DSL element to it's Java DSL counterpart. */
    def asJava: javadsl.FlowGraph.Builder[M] = new javadsl.FlowGraph.Builder()(this)

  }

  object Implicits {

    @tailrec
    private[stream] def findOut[I, O](b: Builder[_], junction: UniformFanOutShape[I, O], n: Int): Outlet[O] = {
      if (n == junction.outArray.length)
        throw new IllegalArgumentException(s"no more outlets free on $junction")
      else if (b.module.downstreams.contains(junction.out(n))) findOut(b, junction, n + 1)
      else junction.out(n)
    }

    @tailrec
    private[stream] def findIn[I, O](b: Builder[_], junction: UniformFanInShape[I, O], n: Int): Inlet[I] = {
      if (n == junction.inSeq.length)
        throw new IllegalArgumentException(s"no more inlets free on $junction")
      else if (b.module.upstreams.contains(junction.in(n))) findIn(b, junction, n + 1)
      else junction.in(n)
    }

    trait CombinerBase[T] extends Any {
      def importAndGetPort(b: Builder[_]): Outlet[T]

      def ~>(to: Inlet[T])(implicit b: Builder[_]): Unit = {
        b.addEdge(importAndGetPort(b), to)
      }

      def ~>[Out](via: Graph[FlowShape[T, Out], Any])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        val s = b.add(via)
        b.addEdge(importAndGetPort(b), s.inlet)
        s.outlet
      }

      def ~>[Out](junction: UniformFanInShape[T, Out])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        def bind(n: Int): Unit = {
          if (n == junction.inSeq.length)
            throw new IllegalArgumentException(s"no more inlets free on $junction")
          else if (b.module.upstreams.contains(junction.in(n))) bind(n + 1)
          else b.addEdge(importAndGetPort(b), junction.in(n))
        }
        bind(0)
        junction.out
      }

      def ~>[Out](junction: UniformFanOutShape[T, Out])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), junction.in)
        try findOut(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledPortOps(e.getMessage)
        }
      }

      def ~>[Out](flow: FlowShape[T, Out])(implicit b: Builder[_]): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), flow.inlet)
        flow.outlet
      }

      def ~>(to: Graph[SinkShape[T], _])(implicit b: Builder[_]): Unit = {
        b.addEdge(importAndGetPort(b), b.add(to).inlet)
      }

      def ~>(to: SinkShape[T])(implicit b: Builder[_]): Unit = {
        b.addEdge(importAndGetPort(b), to.inlet)
      }
    }

    trait ReverseCombinerBase[T] extends Any {
      def importAndGetPortReverse(b: Builder[_]): Inlet[T]

      def <~(from: Outlet[T])(implicit b: Builder[_]): Unit = {
        b.addEdge(from, importAndGetPortReverse(b))
      }

      def <~[In](via: Graph[FlowShape[In, T], _])(implicit b: Builder[_]): ReversePortOps[In] = {
        val s = b.add(via)
        b.addEdge(s.outlet, importAndGetPortReverse(b))
        s.inlet
      }

      def <~[In](junction: UniformFanOutShape[In, T])(implicit b: Builder[_]): ReversePortOps[In] = {
        def bind(n: Int): Unit = {
          if (n == junction.outArray.length)
            throw new IllegalArgumentException(s"no more outlets free on $junction")
          else if (b.module.downstreams.contains(junction.out(n))) bind(n + 1)
          else b.addEdge(junction.out(n), importAndGetPortReverse(b))
        }
        bind(0)
        junction.in
      }

      def <~[In](junction: UniformFanInShape[In, T])(implicit b: Builder[_]): ReversePortOps[In] = {
        b.addEdge(junction.out, importAndGetPortReverse(b))
        try findIn(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledReversePortOps(e.getMessage)
        }
      }

      def <~[In](flow: FlowShape[In, T])(implicit b: Builder[_]): ReversePortOps[In] = {
        b.addEdge(flow.outlet, importAndGetPortReverse(b))
        flow.inlet
      }

      def <~(from: Graph[SourceShape[T], _])(implicit b: Builder[_]): Unit = {
        b.addEdge(b.add(from).outlet, importAndGetPortReverse(b))
      }

      def <~(from: SourceShape[T])(implicit b: Builder[_]): Unit = {
        b.addEdge(from.outlet, importAndGetPortReverse(b))
      }
    }

    class PortOps[Out, Mat](val outlet: Outlet[Out], b: Builder[_]) extends FlowOps[Out, Mat] with CombinerBase[Out] {
      override type Repr[+O, +M] = PortOps[O, M] @uncheckedVariance

      override def withAttributes(attr: Attributes): Repr[Out, Mat] =
        throw new UnsupportedOperationException("Cannot set attributes on chained ops from a junction output port")

      override private[scaladsl] def andThen[U](op: StageModule): Repr[U, Mat] = {
        b.andThen(outlet, op)
        new PortOps(op.shape.outlet.asInstanceOf[Outlet[U]], b)
      }

      override private[scaladsl] def andThenMat[U, Mat2](op: MaterializingStageFactory): Repr[U, Mat2] = {
        // We don't track materialization here
        b.andThen(outlet, op)
        new PortOps(op.shape.outlet.asInstanceOf[Outlet[U]], b)
      }

      override def importAndGetPort(b: Builder[_]): Outlet[Out] = outlet
    }

    class DisabledPortOps[Out, Mat](msg: String) extends PortOps[Out, Mat](null, null) {
      override def importAndGetPort(b: Builder[_]): Outlet[Out] = throw new IllegalArgumentException(msg)
    }

    implicit class ReversePortOps[In](val inlet: Inlet[In]) extends ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = inlet
    }

    class DisabledReversePortOps[In](msg: String) extends ReversePortOps[In](null) {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = throw new IllegalArgumentException(msg)
    }

    implicit class FanInOps[In, Out](val j: UniformFanInShape[In, Out]) extends AnyVal with CombinerBase[Out] with ReverseCombinerBase[In] {
      override def importAndGetPort(b: Builder[_]): Outlet[Out] = j.out
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = findIn(b, j, 0)
    }

    implicit class FanOutOps[In, Out](val j: UniformFanOutShape[In, Out]) extends AnyVal with ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[In] = j.in
    }

    implicit class SinkArrow[T](val s: Graph[SinkShape[T], _]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[T] = b.add(s).inlet
    }

    implicit class SinkShapeArrow[T](val s: SinkShape[T]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[T] = s.inlet
    }

    implicit class FlowShapeArrow[I, O](val f: FlowShape[I, O]) extends AnyVal with ReverseCombinerBase[I] {
      override def importAndGetPortReverse(b: Builder[_]): Inlet[I] = f.inlet

      def <~>[I2, O2, Mat](bidi: Graph[BidiShape[O, O2, I2, I], Mat])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        val shape = b.add(bidi)
        b.addEdge(f.outlet, shape.in1)
        b.addEdge(shape.out2, f.inlet)
        shape
      }

      def <~>[I2, O2](bidi: BidiShape[O, O2, I2, I])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        b.addEdge(f.outlet, bidi.in1)
        b.addEdge(bidi.out2, f.inlet)
        bidi
      }

      def <~>[M](flow: Graph[FlowShape[O, I], M])(implicit b: Builder[_]): Unit = {
        val shape = b.add(flow)
        b.addEdge(shape.outlet, f.inlet)
        b.addEdge(f.outlet, shape.inlet)
      }
    }

    implicit class FlowArrow[I, O, M](val f: Graph[FlowShape[I, O], M]) extends AnyVal {
      def <~>[I2, O2, Mat](bidi: Graph[BidiShape[O, O2, I2, I], Mat])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        val shape = b.add(bidi)
        val flow = b.add(f)
        b.addEdge(flow.outlet, shape.in1)
        b.addEdge(shape.out2, flow.inlet)
        shape
      }

      def <~>[I2, O2](bidi: BidiShape[O, O2, I2, I])(implicit b: Builder[_]): BidiShape[O, O2, I2, I] = {
        val flow = b.add(f)
        b.addEdge(flow.outlet, bidi.in1)
        b.addEdge(bidi.out2, flow.inlet)
        bidi
      }

      def <~>[M2](flow: Graph[FlowShape[O, I], M2])(implicit b: Builder[_]): Unit = {
        val shape = b.add(flow)
        val ff = b.add(f)
        b.addEdge(shape.outlet, ff.inlet)
        b.addEdge(ff.outlet, shape.inlet)
      }
    }

    implicit class BidiFlowShapeArrow[I1, O1, I2, O2](val bidi: BidiShape[I1, O1, I2, O2]) extends AnyVal {
      def <~>[I3, O3](other: BidiShape[O1, O3, I3, I2])(implicit b: Builder[_]): BidiShape[O1, O3, I3, I2] = {
        b.addEdge(bidi.out1, other.in1)
        b.addEdge(other.out2, bidi.in2)
        other
      }

      def <~>[I3, O3, M](otherFlow: Graph[BidiShape[O1, O3, I3, I2], M])(implicit b: Builder[_]): BidiShape[O1, O3, I3, I2] = {
        val other = b.add(otherFlow)
        b.addEdge(bidi.out1, other.in1)
        b.addEdge(other.out2, bidi.in2)
        other
      }

      def <~>(flow: FlowShape[O1, I2])(implicit b: Builder[_]): Unit = {
        b.addEdge(bidi.out1, flow.inlet)
        b.addEdge(flow.outlet, bidi.in2)
      }

      def <~>[M](f: Graph[FlowShape[O1, I2], M])(implicit b: Builder[_]): Unit = {
        val flow = b.add(f)
        b.addEdge(bidi.out1, flow.inlet)
        b.addEdge(flow.outlet, bidi.in2)
      }
    }

    import scala.language.implicitConversions

    implicit def port2flow[T](from: Outlet[T])(implicit b: Builder[_]): PortOps[T, Unit] =
      new PortOps(from, b)

    implicit def fanOut2flow[I, O](j: UniformFanOutShape[I, O])(implicit b: Builder[_]): PortOps[O, Unit] =
      new PortOps(findOut(b, j, 0), b)

    implicit def flow2flow[I, O](f: FlowShape[I, O])(implicit b: Builder[_]): PortOps[O, Unit] =
      new PortOps(f.outlet, b)

    implicit class SourceArrow[T](val s: Graph[SourceShape[T], _]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder[_]): Outlet[T] = b.add(s).outlet
    }

    implicit class SourceShapeArrow[T](val s: SourceShape[T]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder[_]): Outlet[T] = s.outlet
    }
  }
}
