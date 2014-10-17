/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.impl2.Ast.AstNode

import scala.annotation.unchecked.uncheckedVariance

private[scaladsl2] case class GraphFlow[-In, CIn, COut, +Out](inPipe: Pipe[In, CIn], in: UndefinedSource[CIn], graph: PartialFlowGraph, out: UndefinedSink[COut], outPipe: Pipe[COut, Out]) extends Flow[In, Out] {
  override type Repr[+O] = GraphFlow[In @uncheckedVariance, CIn, COut, O]

  private[scaladsl2] def prepend[T](pipe: Pipe[T, In]): GraphFlow[T, CIn, COut, Out] = copy(inPipe = pipe.appendPipe(inPipe))

  private[scaladsl2] def prepend(pipe: SourcePipe[In]): GraphSource[COut, Out] = {
    val newGraph = PartialFlowGraph(graph) { b ⇒
      b.attachSource(in, pipe.appendPipe(inPipe))
    }
    GraphSource(newGraph, out, outPipe)
  }

  private[scaladsl2] def remap(builder: FlowGraphBuilder): (UndefinedSource[CIn], UndefinedSink[COut]) = {
    val nIn = UndefinedSource[CIn]
    val nOut = UndefinedSink[COut]
    builder.remapPartialFlowGraph(graph, Map(in -> nIn, out -> nOut))
    (nIn, nOut)
  }

  private[scaladsl2] def importAndConnect(builder: FlowGraphBuilder, oOut: UndefinedSink[In @uncheckedVariance], oIn: UndefinedSource[Out @uncheckedVariance]): Unit = {
    val (nIn, nOut) = remap(builder)
    builder.connect(oOut, inPipe, nIn)
    builder.connect(nOut, outPipe, oIn)
  }

  def connect[T](flow: Flow[Out, T]): Flow[In, T] = flow match {
    case pipe: Pipe[Out, T] ⇒ copy(outPipe = outPipe.appendPipe(pipe))
    case gFlow: GraphFlow[Out, _, _, T] ⇒
      val (newGraph, nOut) = FlowGraphBuilder(graph) { b ⇒
        val (oIn, oOut) = gFlow.remap(b)
        b.connect(out, outPipe.connect(gFlow.inPipe), oIn)
        (b.partialBuild(), oOut)
      }
      GraphFlow(inPipe, in, newGraph, nOut, gFlow.outPipe)
  }

  override def connect(sink: Sink[Out]) = sink match {
    case sinkPipe: SinkPipe[Out] ⇒
      val newGraph = PartialFlowGraph(this.graph) { builder ⇒
        builder.attachSink(out, outPipe.connect(sinkPipe))
      }
      GraphSink(inPipe, in, newGraph)
    case gSink: GraphSink[Out, Out] ⇒
      val newGraph = PartialFlowGraph(graph) { b ⇒
        val oIn = gSink.remap(b)
        b.connect(out, outPipe.connect(gSink.inPipe), oIn)
      }
      GraphSink(inPipe, in, newGraph)
    case sink: Sink[Out] ⇒ connect(Pipe.empty.withSink(sink)) // recursive, but now it is a SinkPipe
  }

  override private[scaladsl2] def andThen[T](op: AstNode): Repr[T] = copy(outPipe = outPipe.andThen(op))
}

private[scaladsl2] case class GraphSource[COut, +Out](graph: PartialFlowGraph, out: UndefinedSink[COut], outPipe: Pipe[COut, Out]) extends Source[Out] {
  override type Repr[+O] = GraphSource[COut, O]

  private[scaladsl2] def remap(builder: FlowGraphBuilder): UndefinedSink[COut] = {
    val nOut = UndefinedSink[COut]
    builder.remapPartialFlowGraph(graph, Map(out -> nOut))
    nOut
  }

  private[scaladsl2] def importAndConnect(builder: FlowGraphBuilder, oIn: UndefinedSource[Out @uncheckedVariance]): Unit = {
    val nOut = remap(builder)
    builder.connect(nOut, outPipe, oIn)
  }

  override def connect[T](flow: Flow[Out, T]): Source[T] = flow match {
    case pipe: Pipe[Out, T] ⇒ copy(outPipe = outPipe.appendPipe(pipe))
    case gFlow: GraphFlow[Out, _, _, T] ⇒
      val (newGraph, nOut) = FlowGraphBuilder(graph) { b ⇒
        val (oIn, oOut) = gFlow.remap(b)
        b.connect(out, outPipe.connect(gFlow.inPipe), oIn)
        (b.partialBuild(), oOut)
      }
      GraphSource(newGraph, nOut, gFlow.outPipe)
  }

  override def connect(sink: Sink[Out]): RunnableFlow = sink match {
    case sinkPipe: SinkPipe[Out] ⇒
      FlowGraph(this.graph) { implicit builder ⇒
        builder.attachSink(out, outPipe.connect(sinkPipe))
      }
    case gSink: GraphSink[Out, _] ⇒
      FlowGraph(graph) { b ⇒
        val oIn = gSink.remap(b)
        b.connect(out, outPipe.connect(gSink.inPipe), oIn)
      }
    case sink: Sink[Out] ⇒
      connect(Pipe.empty.withSink(sink)) // recursive, but now it is a SinkPipe
  }

  override private[scaladsl2] def andThen[T](op: AstNode): Repr[T] = copy(outPipe = outPipe.andThen(op))
}

private[scaladsl2] case class GraphSink[-In, CIn](inPipe: Pipe[In, CIn], in: UndefinedSource[CIn], graph: PartialFlowGraph) extends Sink[In] {

  private[scaladsl2] def remap(builder: FlowGraphBuilder): UndefinedSource[CIn] = {
    val nIn = UndefinedSource[CIn]
    builder.remapPartialFlowGraph(graph, Map(in -> nIn))
    nIn
  }

  private[scaladsl2] def prepend(pipe: SourcePipe[In]): FlowGraph = {
    FlowGraph(this.graph) { b ⇒
      b.attachSource(in, pipe.connect(inPipe))
    }
  }

  private[scaladsl2] def prepend[T](pipe: Pipe[T, In]): GraphSink[T, CIn] = {
    GraphSink(pipe.appendPipe(inPipe), in, graph)
  }

  private[scaladsl2] def importAndConnect(builder: FlowGraphBuilder, oOut: UndefinedSink[In @uncheckedVariance]): Unit = {
    val nIn = remap(builder)
    builder.connect(oOut, inPipe, nIn)
  }
}
