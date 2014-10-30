/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

object Stage {
  def apply[I1, O1, I2, O2](outbound: Flow[I1, O1], inbound: Flow[I2, O2]): Stage[I1, O1, I2, O2] =
    apply({ implicit b ⇒
      import FlowGraphImplicits._
      val in1 = UndefinedSource[I1]
      val out1 = UndefinedSink[O1]
      val in2 = UndefinedSource[I2]
      val out2 = UndefinedSink[O2]
      in1 ~> outbound ~> out1
      in2 ~> inbound ~> out2
      (in1 -> out1, in2 -> out2)
    })

  def apply[I1, O1, I2, O2](block: FlowGraphBuilder ⇒ ((UndefinedSource[I1], UndefinedSink[O1]), (UndefinedSource[I2], UndefinedSink[O2]))): Stage[I1, O1, I2, O2] = {
    val builder = new FlowGraphBuilder()
    val ((in1, out1), (in2, out2)) = block(builder)
    builder.partialBuild().toStage(in1, out1, in2, out2)
  }
}

class Stage[I1, O1, I2, O2] private[scaladsl] (
  private val in1: UndefinedSource[I1],
  private val out1: UndefinedSink[O1],
  private val in2: UndefinedSource[I2],
  private val out2: UndefinedSink[O2],
  private val graph: PartialFlowGraph) {

  def connect[T1, T2](stage: Stage[O1, T1, T2, I2]): Stage[I1, T1, T2, O2] = {
    val newGraph = PartialFlowGraph(graph) { b ⇒
      b.importPartialFlowGraph(stage.graph)
      b.connect(out1, Flow[O1], stage.in1)
      b.connect(stage.out2, Flow[I2], in2)
    }
    new Stage(in1, stage.out1, stage.in2, out2, newGraph)
  }

  def connect(flow: Flow[O1, I2]): Flow[I1, O2] = {
    Flow(graph) { b ⇒
      b.connect(out1, flow, in2)
      in1 -> out2
    }
  }
}
