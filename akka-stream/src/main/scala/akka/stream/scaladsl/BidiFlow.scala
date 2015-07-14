/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.Graph
import akka.stream.BidiShape
import akka.stream.impl.StreamLayout.Module
import akka.stream.FlowShape
import akka.stream.Attributes

final class BidiFlow[-I1, +O1, -I2, +O2, +Mat](private[stream] override val module: Module) extends Graph[BidiShape[I1, O1, I2, O2], Mat] {
  override val shape = module.shape.asInstanceOf[BidiShape[I1, O1, I2, O2]]

  /**
   * Add the given BidiFlow as the next step in a bidirectional transformation
   * pipeline. By convention protocol stacks are growing to the left: the right most is the bottom
   * layer, the closest to the metal.
   * {{{
   *     +----------------------------+
   *     | Resulting BidiFlow         |
   *     |                            |
   *     |  +------+        +------+  |
   * I1 ~~> |      |  ~O1~> |      | ~~> OO1
   *     |  | this |        | bidi |  |
   * O2 <~~ |      | <~I2~  |      | <~~ II2
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[BidiFlow]] will be the materialized
   * value of the current flow (ignoring the other BidiFlow’s value), use
   * [[BidiFlow#atopMat atopMat]] if a different strategy is needed.
   */
  def atop[OO1, II2, Mat2](bidi: BidiFlow[O1, OO1, II2, I2, Mat2]): BidiFlow[I1, OO1, II2, O2, Mat] = atopMat(bidi)(Keep.left)

  /**
   * Add the given BidiFlow as the next step in a bidirectional transformation
   * pipeline. By convention protocol stacks are growing to the left: the right most is the bottom
   * layer, the closest to the metal.
   * {{{
   *     +----------------------------+
   *     | Resulting BidiFlow         |
   *     |                            |
   *     |  +------+        +------+  |
   * I1 ~~> |      |  ~O1~> |      | ~~> OO1
   *     |  | this |        | bidi |  |
   * O2 <~~ |      | <~I2~  |      | <~~ II2
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting BidiFlow.
   */
  def atopMat[OO1, II2, Mat2, M](bidi: BidiFlow[O1, OO1, II2, I2, Mat2])(combine: (Mat, Mat2) ⇒ M): BidiFlow[I1, OO1, II2, O2, M] = {
    val copy = bidi.module.carbonCopy
    val ins = copy.shape.inlets
    val outs = copy.shape.outlets
    new BidiFlow(module
      .compose(copy, combine)
      .wire(shape.out1, ins(0))
      .wire(outs(1), shape.in2)
      .replaceShape(BidiShape(shape.in1, outs(0), ins(1), shape.out2)))
  }

  /**
   * Add the given Flow as the final step in a bidirectional transformation
   * pipeline. By convention protocol stacks are growing to the left: the right most is the bottom
   * layer, the closest to the metal.
   * {{{
   *     +---------------------------+
   *     | Resulting Flow            |
   *     |                           |
   *     |  +------+        +------+ |
   * I1 ~~> |      |  ~O1~> |      | |
   *     |  | this |        | flow | |
   * O2 <~~ |      | <~I2~  |      | |
   *     |  +------+        +------+ |
   *     +---------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * [[BidiFlow#joinMat joinMat]] if a different strategy is needed.
   */
  def join[Mat2](flow: Flow[O1, I2, Mat2]): Flow[I1, O2, Mat] = joinMat(flow)(Keep.left)

  /**
   * Add the given Flow as the final step in a bidirectional transformation
   * pipeline. By convention protocol stacks are growing to the left: the right most is the bottom
   * layer, the closest to the metal.
   * {{{
   *     +---------------------------+
   *     | Resulting Flow            |
   *     |                           |
   *     |  +------+        +------+ |
   * I1 ~~> |      |  ~O1~> |      | |
   *     |  | this |        | flow | |
   * O2 <~~ |      | <~I2~  |      | |
   *     |  +------+        +------+ |
   *     +---------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting [[Flow]].
   */
  def joinMat[Mat2, M](flow: Flow[O1, I2, Mat2])(combine: (Mat, Mat2) ⇒ M): Flow[I1, O2, M] = {
    val copy = flow.module.carbonCopy
    val in = copy.shape.inlets.head
    val out = copy.shape.outlets.head
    new Flow(module
      .compose(copy, combine)
      .wire(shape.out1, in)
      .wire(out, shape.in2)
      .replaceShape(FlowShape(shape.in1, shape.out2)))
  }

  /**
   * Turn this BidiFlow around by 180 degrees, logically flipping it upside down in a protocol stack.
   */
  def reversed: BidiFlow[I2, O2, I1, O1, Mat] = new BidiFlow(module.replaceShape(shape.reversed))

  override def withAttributes(attr: Attributes): BidiFlow[I1, O1, I2, O2, Mat] =
    new BidiFlow(module.withAttributes(attr).nest())

  override def named(name: String): BidiFlow[I1, O1, I2, O2, Mat] =
    withAttributes(Attributes.name(name))
}

object BidiFlow extends BidiFlowApply {

  /**
   * A graph with the shape of a flow logically is a flow, this method makes
   * it so also in type.
   */
  def wrap[I1, O1, I2, O2, Mat](graph: Graph[BidiShape[I1, O1, I2, O2], Mat]): BidiFlow[I1, O1, I2, O2, Mat] =
    graph match {
      case bidi: BidiFlow[I1, O1, I2, O2, Mat] ⇒ bidi
      case other                               ⇒ new BidiFlow(other.module)
    }

  /**
   * Wraps two Flows to create a ''BidiFlow''. The materialized value of the resulting BidiFlow is determined
   * by the combiner function passed in the second argument list.
   *
   * {{{
   *     +----------------------------+
   *     | Resulting BidiFlow         |
   *     |                            |
   *     |  +----------------------+  |
   * I1 ~~> |        Flow1         | ~~> O1
   *     |  +----------------------+  |
   *     |                            |
   *     |  +----------------------+  |
   * O2 <~~ |        Flow2         | <~~ I2
   *     |  +----------------------+  |
   *     +----------------------------+
   * }}}
   *
   */
  def wrap[I1, O1, I2, O2, M1, M2, M](
    flow1: Graph[FlowShape[I1, O1], M1],
    flow2: Graph[FlowShape[I2, O2], M2])(combine: (M1, M2) ⇒ M): BidiFlow[I1, O1, I2, O2, M] = {
    BidiFlow(flow1, flow2)(combine) { implicit b ⇒
      (f1, f2) ⇒
        BidiShape(f1.inlet, f1.outlet, f2.inlet, f2.outlet)
    }
  }

  /**
   * Create a BidiFlow where the top and bottom flows are just one simple mapping
   * stage each, expressed by the two functions.
   */
  def apply[I1, O1, I2, O2](outbound: I1 ⇒ O1, inbound: I2 ⇒ O2): BidiFlow[I1, O1, I2, O2, Unit] =
    BidiFlow() { b ⇒
      val top = b.add(Flow[I1].map(outbound))
      val bottom = b.add(Flow[I2].map(inbound))
      BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
    }
}
