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

object Merge {
  def apply[T](inputPorts: Int, attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanInShape[T, T], Unit] =
    new Graph[UniformFanInShape[T, T], Unit] {
      val shape = new UniformFanInShape[T, T](inputPorts)
      val module = new MergeModule(shape, OperationAttributes.name("Merge") and attributes)
    }
}

object MergePreferred {
  import FanInShape._
  final class MergePreferredShape[T](val secondaryPorts: Int, _init: Init[T]) extends UniformFanInShape[T, T](secondaryPorts, _init) {
    def this(secondaryPorts: Int, name: String) = this(secondaryPorts, Name(name))
    override protected def construct(init: Init[T]): FanInShape[T] = new MergePreferredShape(secondaryPorts, init)
    override def deepCopy(): MergePreferredShape[T] = super.deepCopy().asInstanceOf[MergePreferredShape[T]]

    val preferred = newInlet[T]("preferred")
  }

  def apply[T](secondaryPorts: Int, attributes: OperationAttributes = OperationAttributes.none): Graph[MergePreferredShape[T], Unit] =
    new Graph[MergePreferredShape[T], Unit] {
      val shape = new MergePreferredShape[T](secondaryPorts, "MergePreferred")
      val module = new MergePreferredModule(shape, OperationAttributes.name("MergePreferred") and attributes)
    }
}

object Broadcast {
  def apply[T](outputPorts: Int, attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanOutShape[T, T], Unit] =
    new Graph[UniformFanOutShape[T, T], Unit] {
      val shape = new UniformFanOutShape[T, T](outputPorts)
      val module = new BroadcastModule(shape, OperationAttributes.name("Broadcast") and attributes)
    }
}

object Balance {
  def apply[T](outputPorts: Int, waitForAllDownstreams: Boolean = false, attributes: OperationAttributes = OperationAttributes.none): Graph[UniformFanOutShape[T, T], Unit] =
    new Graph[UniformFanOutShape[T, T], Unit] {
      val shape = new UniformFanOutShape[T, T](outputPorts)
      val module = new BalanceModule(shape, waitForAllDownstreams, OperationAttributes.name("Balance") and attributes)
    }
}

object Zip {
  def apply[A, B](attributes: OperationAttributes = OperationAttributes.none): Graph[FanInShape2[A, B, (A, B)], Unit] =
    new Graph[FanInShape2[A, B, (A, B)], Unit] {
      val shape = new FanInShape2[A, B, (A, B)]("Zip")
      val module = new ZipWith2Module[A, B, (A, B)](shape, Keep.both, OperationAttributes.name("Zip") and attributes)
    }
}

object ZipWith extends ZipWithApply

object Unzip {
  def apply[A, B](attributes: OperationAttributes = OperationAttributes.none): Graph[FanOutShape2[(A, B), A, B], Unit] =
    new Graph[FanOutShape2[(A, B), A, B], Unit] {
      val shape = new FanOutShape2[(A, B), A, B]("Unzip")
      val module = new UnzipModule(shape, OperationAttributes.name("Unzip") and attributes)
    }
}

object Concat {
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
        throw new IllegalStateException(
          "Cannot build the RunnableFlow because there are unconnected ports: " +
            (moduleInProgress.outPorts ++ moduleInProgress.inPorts).mkString(", "))
      }
      new RunnableFlow(moduleInProgress)
    }

    private[stream] def buildSource[T, Mat](outlet: Outlet[T]): Source[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalStateException("Cannot build the Source since no ports remain open")
      if (!moduleInProgress.isSource)
        throw new IllegalStateException(
          s"Cannot build Source with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalStateException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      new Source(moduleInProgress.replaceShape(SourceShape(outlet)))
    }

    private[stream] def buildFlow[In, Out, Mat](inlet: Inlet[In], outlet: Outlet[Out]): Flow[In, Out, Mat] = {
      if (!moduleInProgress.isFlow)
        throw new IllegalStateException(
          s"Cannot build Flow with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.outPorts.head != outlet)
        throw new IllegalStateException(s"provided Outlet $outlet does not equal the module’s open Outlet ${moduleInProgress.outPorts.head}")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalStateException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Flow(moduleInProgress.replaceShape(FlowShape(inlet, outlet)))
    }

    private[stream] def buildSink[T, Mat](inlet: Inlet[T]): Sink[T, Mat] = {
      if (moduleInProgress.isRunnable)
        throw new IllegalStateException("Cannot build the Sink since no ports remain open")
      if (!moduleInProgress.isSink)
        throw new IllegalStateException(
          s"Cannot build Sink with open inputs (${moduleInProgress.inPorts.mkString(",")}) and outputs (${moduleInProgress.outPorts.mkString(",")})")
      if (moduleInProgress.inPorts.head != inlet)
        throw new IllegalStateException(s"provided Inlet $inlet does not equal the module’s open Inlet ${moduleInProgress.inPorts.head}")
      new Sink(moduleInProgress.replaceShape(SinkShape(inlet)))
    }

    private[stream] def module: Module = moduleInProgress

  }

  object Implicits {

    @tailrec
    private def find[I, O](b: Builder, junction: UniformFanOutShape[I, O], n: Int): Outlet[O] = {
      if (n == junction.outArray.length)
        throw new IllegalStateException(s"no more outlets free on $junction")
      else if (b.module.downstreams.contains(junction.out(n))) find(b, junction, n + 1)
      else junction.out(n)
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
            throw new IllegalStateException(s"no more inlets free on $junction")
          else if (b.module.upstreams.contains(junction.in(n))) bind(n + 1)
          else b.addEdge(importAndGetPort(b), junction.in(n))
        }
        bind(0)
        junction.out
      }

      def ~>[Out](junction: UniformFanOutShape[T, Out])(implicit b: Builder): PortOps[Out, Unit] = {
        b.addEdge(importAndGetPort(b), junction.in)
        find(b, junction, 0)
      }

      def ~>(to: Sink[T, _])(implicit b: Builder): Unit = {
        b.addEdge(importAndGetPort(b), b.add(to))
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

    import scala.language.implicitConversions

    implicit def port2flow[T](from: Outlet[T])(implicit b: Builder): PortOps[T, Unit] =
      new PortOps(from, b)

    implicit def fanOut2flow[I, O](j: UniformFanOutShape[I, O])(implicit b: Builder): PortOps[O, Unit] =
      new PortOps(find(b, j, 0), b)

    implicit class SourceArrow[T](val s: Source[T, _]) extends AnyVal with CombinerBase[T] {
      override def importAndGetPort(b: Builder): Outlet[T] = b.add(s)
    }

  }

}
