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
import OperationAttributes.name
import scala.collection.immutable
import scala.annotation.unchecked.uncheckedVariance
import scala.annotation.tailrec

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * A `Merge` has one `out` port and one or more `in` ports.
 */
object Merge {
  /**
   * Create a new `Merge` with the specified number of input ports and attributes.
   *
   * @param inputPorts number of input ports
   * @param attributes optional attributes
   */
  def apply[T](inputPorts: Int, attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanInShape[T, T], Unit] =
    new Graph[UniformFanInShape[T, T], Unit] {
      val shape = new UniformFanInShape[T, T](inputPorts)
      val module = new MergeModule(shape, OperationAttributes.name("Merge") and attributes)
    }
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * A `MergePreferred` has one `out` port, one `preferred` input port and 0 or more secondary `in` ports.
 */
object MergePreferred {
  import FanInShape._
  final class MergePreferredShape[T](val secondaryPorts: Int, _init: Init[T]) extends UniformFanInShape[T, T](secondaryPorts, _init) {
    def this(secondaryPorts: Int, name: String) = this(secondaryPorts, Name(name))
    override protected def construct(init: Init[T]): FanInShape[T] = new MergePreferredShape(secondaryPorts, init)
    override def deepCopy(): MergePreferredShape[T] = super.deepCopy().asInstanceOf[MergePreferredShape[T]]

    val preferred = newInlet[T]("preferred")
  }

  /**
   * Create a new `PreferredMerge` with the specified number of secondary input ports and attributes.
   *
   * @param secondaryPorts number of secondary input ports
   * @param attributes optional attributes
   */
  def apply[T](secondaryPorts: Int, attributes: OperationAttributes = OperationAttributes.none): Graph[MergePreferredShape[T], Unit] =
    new Graph[MergePreferredShape[T], Unit] {
      val shape = new MergePreferredShape[T](secondaryPorts, "MergePreferred")
      val module = new MergePreferredModule(shape, OperationAttributes.name("MergePreferred") and attributes)
    }
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * the other streams. It will not shut down until the subscriptions
 * for at least two downstream subscribers have been established.
 *
 * A `Broadcast` has one `in` port and 2 or more `out` ports.
 */
object Broadcast {
  /**
   * Create a new `Broadcast` with the specified number of output ports and attributes.
   *
   * @param outputPorts number of output ports
   * @param attributes optional attributes
   */
  def apply[T](outputPorts: Int, attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanOutShape[T, T], Unit] =
    new Graph[UniformFanOutShape[T, T], Unit] {
      val shape = new UniformFanOutShape[T, T](outputPorts)
      val module = new BroadcastModule(shape, OperationAttributes.name("Broadcast") and attributes)
    }
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * one of the other streams. It will not shut down until the subscriptions
 * for at least two downstream subscribers have been established.
 *
 * A `Balance` has one `in` port and 2 or more `out` ports.
 */
object Balance {
  /**
   * Create a new `Balance` with the specified number of output ports and attributes.
   *
   * @param outputPorts number of output ports
   * @param waitForAllDownstreams if you use `waitForAllDownstreams = true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element,
   *   default value is `false`
   * @param attributes optional attributes
   */
  def apply[T](outputPorts: Int, waitForAllDownstreams: Boolean = false, attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanOutShape[T, T], Unit] =
    new Graph[UniformFanOutShape[T, T], Unit] {
      val shape = new UniformFanOutShape[T, T](outputPorts)
      val module = new BalanceModule(shape, waitForAllDownstreams, OperationAttributes.name("Balance") and attributes)
    }
}

/**
 * Combine the elements of 2 streams into a stream of tuples.
 *
 * A `Zip` has a `left` and a `right` input port and one `out` port
 */
object Zip {
  /**
   * Create a new `Zip` with the specified attributes.
   *
   * @param attributes optional attributes
   */
  def apply[A, B](attributes: OperationAttributes = OperationAttributes.none): Graph[FanInShape2[A, B, (A, B)], Unit] =
    new Graph[FanInShape2[A, B, (A, B)], Unit] {
      val shape = new FanInShape2[A, B, (A, B)]("Zip")
      val module = new ZipWith2Module[A, B, (A, B)](shape, Keep.both, OperationAttributes.name("Zip") and attributes)
    }
}

/**
 * Combine the elements of multiple streams into a stream of the combined elements.
 */
object ZipWith extends ZipWithApply

/**
 * Takes a stream of pair elements and splits each pair to two output streams.
 *
 * An `Unzip` has one `in` port and one `left` and one `right` output port.
 */
object Unzip {
  /**
   * Create a new `Unzip` with the specified attributes.
   *
   * @param attributes optional attributes
   */
  def apply[A, B](attributes: OperationAttributes = OperationAttributes.none): Graph[FanOutShape2[(A, B), A, B], Unit] =
    new Graph[FanOutShape2[(A, B), A, B], Unit] {
      val shape = new FanOutShape2[(A, B), A, B]("Unzip")
      val module = new UnzipModule(shape, OperationAttributes.name("Unzip") and attributes)
    }
}

/**
 * Takes two streams and outputs one stream formed from the two input streams
 * by first emitting all of the elements from the first stream and then emitting
 * all of the elements from the second stream.
 *
 * A `Concat` has one `first` port, one `second` port and one `out` port.
 */
object Concat {
  /**
   * Create a new `Concat` with the specified attributes.
   *
   * @param attributes optional attributes
   */
  def apply[A](attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanInShape[A, A], Unit] =
    new Graph[UniformFanInShape[A, A], Unit] {
      val shape = new UniformFanInShape[A, A](2)
      val module = new ConcatModule(shape, OperationAttributes.name("Concat") and attributes)
    }
}

object FlowGraph extends GraphApply {

  class Builder private[stream] () {
    private var moduleInProgress: Module = EmptyModule

    def addEdge[A, B, M](from: Outlet[A], via: Flow[A, B, M], to: Inlet[B]): Unit = {
      val flowCopy = via.module.carbonCopy
      moduleInProgress =
        moduleInProgress
          .grow(flowCopy)
          .connect(from, flowCopy.shape.inlets.head)
          .connect(flowCopy.shape.outlets.head, to)
    }

    def addEdge[T](from: Outlet[T], to: Inlet[T]): Unit = {
      moduleInProgress = moduleInProgress.connect(from, to)
    }

    /**
     * Import a graph into this module, performing a deep copy, discarding its
     * materialized value and returning the copied Ports that are now to be
     * connected.
     */
    def add[S <: Shape](graph: Graph[S, _]): S = {
      if (StreamLayout.debug) graph.module.validate()
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.grow(copy)
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    /**
     * INTERNAL API.
     *
     * This is only used by the materialization-importing apply methods of Source,
     * Flow, Sink and Graph.
     */
    private[stream] def add[S <: Shape, A, B](graph: Graph[S, _], combine: (A, B) ⇒ Any): S = {
      if (StreamLayout.debug) graph.module.validate()
      val copy = graph.module.carbonCopy
      moduleInProgress = moduleInProgress.grow(copy, combine)
      graph.shape.copyFromPorts(copy.shape.inlets, copy.shape.outlets).asInstanceOf[S]
    }

    def add[T](s: Source[T, _]): Outlet[T] = add(s: Graph[SourceShape[T], _]).outlet
    def add[T](s: Sink[T, _]): Inlet[T] = add(s: Graph[SinkShape[T], _]).inlet

    private[stream] def andThen(port: OutPort, op: StageModule): Unit = {
      moduleInProgress =
        moduleInProgress
          .grow(op)
          .connect(port, op.inPort)
    }

    private[stream] def buildRunnable[Mat](): RunnableFlow[Mat] = {
      if (!moduleInProgress.isRunnable) {
        throw new IllegalArgumentException(
          "Cannot build the RunnableFlow because there are unconnected ports: " +
            (moduleInProgress.outPorts ++ moduleInProgress.inPorts).mkString(", "))
      }
      new RunnableFlow(moduleInProgress)
    }

    private[stream] def buildSource[T, Mat](outlet: Outlet[T]): Source[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalArgumentException("Cannot build the Source since no ports remain open")
      if (!moduleInProgress.isSource)
        throw new IllegalArgumentException(
          s"Cannot build Source with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalArgumentException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      new Source(moduleInProgress.replaceShape(SourceShape(outlet)))
    }

    private[stream] def buildFlow[In, Out, Mat](inlet: Inlet[In], outlet: Outlet[Out]): Flow[In, Out, Mat] = {
      if (!moduleInProgress.isFlow)
        throw new IllegalArgumentException(
          s"Cannot build Flow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalArgumentException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalArgumentException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Flow(moduleInProgress.replaceShape(FlowShape(inlet, outlet)))
    }

    private[stream] def buildBidiFlow[I1, O1, I2, O2, Mat](shape: BidiShape[I1, O1, I2, O2]): BidiFlow[I1, O1, I2, O2, Mat] = {
      if (!moduleInProgress.isBidiFlow)
        throw new IllegalArgumentException(
          s"Cannot build BidiFlow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.toSet != shape.outlets.toSet)
        throw new IllegalArgumentException(s"provided Outlets [${shape.outlets.mkString(",")}] does not equal the module’s open Outlets [${moduleInProgress.outPorts.mkString(",")}]")
      if (moduleInProgress.inPorts.toSet != shape.inlets.toSet)
        throw new IllegalArgumentException(s"provided Inlets [${shape.inlets.mkString(",")}] does not equal the module’s open Inlets [${moduleInProgress.inPorts.mkString(",")}]")
      new BidiFlow(moduleInProgress.replaceShape(shape))
    }

    private[stream] def buildSink[T, Mat](inlet: Inlet[T]): Sink[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalArgumentException("Cannot build the Sink since no ports remain open")
      if (!moduleInProgress.isSink)
        throw new IllegalArgumentException(
          s"Cannot build Sink with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalArgumentException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Sink(moduleInProgress.replaceShape(SinkShape(inlet)))
    }

    private[stream] def module: Module = moduleInProgress

  }

  object Implicits {

    @tailrec
    private[stream] def findOut[I, O](b: Builder, junction: UniformFanOutShape[I, O], n: Int): Outlet[O] = {
      if (n == junction.outArray.length)
        throw new IllegalArgumentException(s"no more outlets free on $junction")
      else if (b.module.downstreams.contains(junction.out(n))) findOut(b, junction, n + 1)
      else junction.out(n)
    }

    @tailrec
    private[stream] def findIn[I, O](b: Builder, junction: UniformFanInShape[I, O], n: Int): Inlet[I] = {
      if (n == junction.inArray.length)
        throw new IllegalArgumentException(s"no more inlets free on $junction")
      else if (b.module.upstreams.contains(junction.in(n))) findIn(b, junction, n + 1)
      else junction.in(n)
    }

    trait CombinerBase[T] extends Any {
      def importAndGetPort(b: Builder): Outlet[T]

      def ~>(to: Inlet[T])(implicit b: Builder): Unit = {
        b.addEdge(importAndGetPort(b), to)
      }

      def ~>[Out](via: Flow[T, Out, _])(implicit b: Builder): PortOps[Out, Unit] = {
        val s = b.add(via)
        b.addEdge(importAndGetPort(b), s.inlet)
        s.outlet
      }

      def ~>[Out](junction: UniformFanInShape[T, Out])(implicit b: Builder): PortOps[Out, Unit] = {
        def bind(n: Int): Unit = {
          if (n == junction.inArray.length)
            throw new IllegalArgumentException(s"no more inlets free on $junction")
          else if (b.module.upstreams.contains(junction.in(n))) bind(n + 1)
          else b.addEdge(importAndGetPort(b), junction.in(n))
        }
        bind(0)
        junction.out
      }

      def ~>[Out](junction: UniformFanOutShape[T, Out])(implicit b: Builder): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), junction.in)
        try findOut(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledPortOps(e.getMessage)
        }
      }

      def ~>[Out](flow: FlowShape[T, Out])(implicit b: Builder): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), flow.inlet)
        flow.outlet
      }

      def ~>(to: Sink[T, _])(implicit b: Builder): Unit = {
        b.addEdge(importAndGetPort(b), b.add(to))
      }

      def ~>(to: SinkShape[T])(implicit b: Builder): Unit = {
        b.addEdge(importAndGetPort(b), to.inlet)
      }
    }

    trait ReverseCombinerBase[T] extends Any {
      def importAndGetPortReverse(b: Builder): Inlet[T]

      def <~(from: Outlet[T])(implicit b: Builder): Unit = {
        b.addEdge(from, importAndGetPortReverse(b))
      }

      def <~[In](via: Flow[In, T, _])(implicit b: Builder): ReversePortOps[In] = {
        val s = b.add(via)
        b.addEdge(s.outlet, importAndGetPortReverse(b))
        s.inlet
      }

      def <~[In](junction: UniformFanOutShape[In, T])(implicit b: Builder): ReversePortOps[In] = {
        def bind(n: Int): Unit = {
          if (n == junction.outArray.length)
            throw new IllegalArgumentException(s"no more outlets free on $junction")
          else if (b.module.downstreams.contains(junction.out(n))) bind(n + 1)
          else b.addEdge(junction.out(n), importAndGetPortReverse(b))
        }
        bind(0)
        junction.in
      }

      def <~[In](junction: UniformFanInShape[In, T])(implicit b: Builder): ReversePortOps[In] = {
        b.addEdge(junction.out, importAndGetPortReverse(b))
        try findIn(b, junction, 0)
        catch {
          case e: IllegalArgumentException ⇒ new DisabledReversePortOps(e.getMessage)
        }
      }

      def <~[In](flow: FlowShape[In, T])(implicit b: Builder): ReversePortOps[In] = {
        b.addEdge(flow.outlet, importAndGetPortReverse(b))
        flow.inlet
      }

      def <~(from: Source[T, _])(implicit b: Builder): Unit = {
        b.addEdge(b.add(from), importAndGetPortReverse(b))
      }

      def <~(from: SourceShape[T])(implicit b: Builder): Unit = {
        b.addEdge(from.outlet, importAndGetPortReverse(b))
      }
    }

    class PortOps[Out, Mat](val outlet: Outlet[Out], b: Builder) extends FlowOps[Out, Mat] with CombinerBase[Out] {
      override type Repr[+O, +M] = PortOps[O, M] @uncheckedVariance

      override def withAttributes(attr: OperationAttributes): Repr[Out, Mat] =
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

      override def importAndGetPort(b: Builder): Outlet[Out] = outlet
    }

    class DisabledPortOps[Out, Mat](msg: String) extends PortOps[Out, Mat](null, null) {
      override def importAndGetPort(b: Builder): Outlet[Out] = throw new IllegalArgumentException(msg)
    }

    implicit class ReversePortOps[In](val inlet: Inlet[In]) extends ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder): Inlet[In] = inlet
    }

    class DisabledReversePortOps[In](msg: String) extends ReversePortOps[In](null) {
      override def importAndGetPortReverse(b: Builder): Inlet[In] = throw new IllegalArgumentException(msg)
    }

    implicit class FanInOps[In, Out](val j: UniformFanInShape[In, Out]) extends AnyVal with CombinerBase[Out] with ReverseCombinerBase[In] {
      override def importAndGetPort(b: Builder): Outlet[Out] = j.out
      override def importAndGetPortReverse(b: Builder): Inlet[In] = findIn(b, j, 0)
    }

    implicit class FanOutOps[In, Out](val j: UniformFanOutShape[In, Out]) extends AnyVal with ReverseCombinerBase[In] {
      override def importAndGetPortReverse(b: Builder): Inlet[In] = j.in
    }

    implicit class SinkArrow[T](val s: Sink[T, _]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder): Inlet[T] = b.add(s)
    }

    implicit class SinkShapeArrow[T](val s: SinkShape[T]) extends AnyVal with ReverseCombinerBase[T] {
      override def importAndGetPortReverse(b: Builder): Inlet[T] = s.inlet
    }

    implicit class FlowShapeArrow[I, O](val f: FlowShape[I, O]) extends AnyVal with ReverseCombinerBase[I] {
      override def importAndGetPortReverse(b: Builder): Inlet[I] = f.inlet

      def <~>[I2, O2, Mat](bidi: BidiFlow[O, O2, I2, I, Mat])(implicit b: Builder): BidiShape[O, O2, I2, I] = {
        val shape = b.add(bidi)
        b.addEdge(f.outlet, shape.in1)
        b.addEdge(shape.out2, f.inlet)
        shape
      }

      def <~>[I2, O2](bidi: BidiShape[O, O2, I2, I])(implicit b: Builder): BidiShape[O, O2, I2, I] = {
        b.addEdge(f.outlet, bidi.in1)
        b.addEdge(bidi.out2, f.inlet)
        bidi
      }

      def <~>[M](flow: Flow[O, I, M])(implicit b: Builder): Unit = {
        val shape = b.add(flow)
        b.addEdge(shape.outlet, f.inlet)
        b.addEdge(f.outlet, shape.inlet)
      }
    }

    implicit class FlowArrow[I, O, M](val f: Flow[I, O, M]) extends AnyVal {
      def <~>[I2, O2, Mat](bidi: BidiFlow[O, O2, I2, I, Mat])(implicit b: Builder): BidiShape[O, O2, I2, I] = {
        val shape = b.add(bidi)
        val flow = b.add(f)
        b.addEdge(flow.outlet, shape.in1)
        b.addEdge(shape.out2, flow.inlet)
        shape
      }

      def <~>[I2, O2](bidi: BidiShape[O, O2, I2, I])(implicit b: Builder): BidiShape[O, O2, I2, I] = {
        val flow = b.add(f)
        b.addEdge(flow.outlet, bidi.in1)
        b.addEdge(bidi.out2, flow.inlet)
        bidi
      }

      def <~>[M2](flow: Flow[O, I, M2])(implicit b: Builder): Unit = {
        val shape = b.add(flow)
        val ff = b.add(f)
        b.addEdge(shape.outlet, ff.inlet)
        b.addEdge(ff.outlet, shape.inlet)
      }
    }

    implicit class BidiFlowShapeArrow[I1, O1, I2, O2](val bidi: BidiShape[I1, O1, I2, O2]) extends AnyVal {
      def <~>[I3, O3](other: BidiShape[O1, O3, I3, I2])(implicit b: Builder): BidiShape[O1, O3, I3, I2] = {
        b.addEdge(bidi.out1, other.in1)
        b.addEdge(other.out2, bidi.in2)
        other
      }

      def <~>[I3, O3, M](otherFlow: BidiFlow[O1, O3, I3, I2, M])(implicit b: Builder): BidiShape[O1, O3, I3, I2] = {
        val other = b.add(otherFlow)
        b.addEdge(bidi.out1, other.in1)
        b.addEdge(other.out2, bidi.in2)
        other
      }

      def <~>(flow: FlowShape[O1, I2])(implicit b: Builder): Unit = {
        b.addEdge(bidi.out1, flow.inlet)
        b.addEdge(flow.outlet, bidi.in2)
      }

      def <~>[M](f: Flow[O1, I2, M])(implicit b: Builder): Unit = {
        val flow = b.add(f)
        b.addEdge(bidi.out1, flow.inlet)
        b.addEdge(flow.outlet, bidi.in2)
      }
    }

    import scala.language.implicitConversions

    implicit def port2flow[T](from: Outlet[T])(implicit b: Builder): PortOps[T, Unit] =
      new PortOps(from, b)

    implicit def fanOut2flow[I, O](j: UniformFanOutShape[I, O])(implicit b: Builder): PortOps[O, Unit] =
      new PortOps(findOut(b, j, 0), b)

    implicit def flow2flow[I, O](f: FlowShape[I, O])(implicit b: Builder): PortOps[O, Unit] =
      new PortOps(f.outlet, b)

    implicit class SourceArrow[T](val s: Source[T, _]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder): Outlet[T] = b.add(s)
    }

    implicit class SourceShapeArrow[T](val s: SourceShape[T]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder): Outlet[T] = s.outlet
    }

  }

}
