/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.Timers

import scala.concurrent.duration.FiniteDuration

final class BidiFlow[-I1, +O1, -I2, +O2, +Mat](override val module: Module) extends Graph[BidiShape[I1, O1, I2, O2], Mat] {
  override def shape = module.shape.asInstanceOf[BidiShape[I1, O1, I2, O2]]

  def asJava: javadsl.BidiFlow[I1, O1, I2, O2, Mat] = new javadsl.BidiFlow(this)

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
  def atop[OO1, II2, Mat2](bidi: Graph[BidiShape[O1, OO1, II2, I2], Mat2]): BidiFlow[I1, OO1, II2, O2, Mat] = atopMat(bidi)(Keep.left)

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
  def atopMat[OO1, II2, Mat2, M](bidi: Graph[BidiShape[O1, OO1, II2, I2], Mat2])(combine: (Mat, Mat2) ⇒ M): BidiFlow[I1, OO1, II2, O2, M] = {
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
  def join[Mat2](flow: Graph[FlowShape[O1, I2], Mat2]): Flow[I1, O2, Mat] = joinMat(flow)(Keep.left)

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
  def joinMat[Mat2, M](flow: Graph[FlowShape[O1, I2], Mat2])(combine: (Mat, Mat2) ⇒ M): Flow[I1, O2, M] = {
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
  def reversed: BidiFlow[I2, O2, I1, O1, Mat] = new BidiFlow(module.replaceShape(BidiShape(shape.in2, shape.out2, shape.in1, shape.out1)))

  /**
   * Transform only the materialized value of this BidiFlow, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): BidiFlow[I1, O1, I2, O2, Mat2] =
    new BidiFlow(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Change the attributes of this [[Source]] to the given ones and seal the list
   * of attributes. This means that further calls will not be able to remove these
   * attributes, but instead add new ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def withAttributes(attr: Attributes): BidiFlow[I1, O1, I2, O2, Mat] =
    new BidiFlow(module.withAttributes(attr))

  /**
   * Add the given attributes to this Source. Further calls to `withAttributes`
   * will not remove these attributes. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def addAttributes(attr: Attributes): BidiFlow[I1, O1, I2, O2, Mat] =
    withAttributes(module.attributes and attr)

  /**
   * Add a ``name`` attribute to this Flow.
   */
  override def named(name: String): BidiFlow[I1, O1, I2, O2, Mat] =
    addAttributes(Attributes.name(name))

  override def async: BidiFlow[I1, O1, I2, O2, Mat] =
    addAttributes(Attributes.asyncBoundary)
}

object BidiFlow {
  private[this] val _identity: BidiFlow[Any, Any, Any, Any, NotUsed] =
    BidiFlow.fromFlows(Flow[Any], Flow[Any])

  def identity[A, B]: BidiFlow[A, A, B, B, NotUsed] = _identity.asInstanceOf[BidiFlow[A, A, B, B, NotUsed]]

  /**
   * A graph with the shape of a flow logically is a flow, this method makes
   * it so also in type.
   */
  def fromGraph[I1, O1, I2, O2, Mat](graph: Graph[BidiShape[I1, O1, I2, O2], Mat]): BidiFlow[I1, O1, I2, O2, Mat] =
    graph match {
      case bidi: BidiFlow[I1, O1, I2, O2, Mat]         ⇒ bidi
      case bidi: javadsl.BidiFlow[I1, O1, I2, O2, Mat] ⇒ bidi.asScala
      case other                                       ⇒ new BidiFlow(other.module)
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
  def fromFlowsMat[I1, O1, I2, O2, M1, M2, M](
    flow1: Graph[FlowShape[I1, O1], M1],
    flow2: Graph[FlowShape[I2, O2], M2])(combine: (M1, M2) ⇒ M): BidiFlow[I1, O1, I2, O2, M] =
    fromGraph(GraphDSL.create(flow1, flow2)(combine) { implicit b ⇒ (f1, f2) ⇒ BidiShape(f1.in, f1.out, f2.in, f2.out)
    })

  /**
   * Wraps two Flows to create a ''BidiFlow''. The materialized value of the resulting BidiFlow is Unit.
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
  def fromFlows[I1, O1, I2, O2, M1, M2](
    flow1: Graph[FlowShape[I1, O1], M1],
    flow2: Graph[FlowShape[I2, O2], M2]): BidiFlow[I1, O1, I2, O2, NotUsed] =
    fromFlowsMat(flow1, flow2)(Keep.none)

  /**
   * Create a BidiFlow where the top and bottom flows are just one simple mapping
   * stage each, expressed by the two functions.
   */
  def fromFunctions[I1, O1, I2, O2](outbound: I1 ⇒ O1, inbound: I2 ⇒ O2): BidiFlow[I1, O1, I2, O2, NotUsed] =
    fromFlows(Flow[I1].map(outbound), Flow[I2].map(inbound))

  /**
   * If the time between two processed elements *in any direction* exceed the provided timeout, the stream is failed
   * with a [[scala.concurrent.TimeoutException]].
   *
   * There is a difference between this stage and having two idleTimeout Flows assembled into a BidiStage.
   * If the timeout is configured to be 1 seconds, then this stage will not fail even though there are elements flowing
   * every second in one direction, but no elements are flowing in the other direction. I.e. this stage considers
   * the *joint* frequencies of the elements in both directions.
   */
  def bidirectionalIdleTimeout[I, O](timeout: FiniteDuration): BidiFlow[I, I, O, O, NotUsed] =
    fromGraph(new Timers.IdleTimeoutBidi(timeout))

}
