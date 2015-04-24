/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.japi.function
import akka.stream.scaladsl
import akka.stream.Graph
import akka.stream.BidiShape
import akka.stream.OperationAttributes

object BidiFlow {

  val factory: BidiFlowCreate = new BidiFlowCreate {}

  /**
   * A graph with the shape of a BidiFlow logically is a BidiFlow, this method makes
   * it so also in type.
   */
  def wrap[I1, O1, I2, O2, M](g: Graph[BidiShape[I1, O1, I2, O2], M]): BidiFlow[I1, O1, I2, O2, M] = new BidiFlow(scaladsl.BidiFlow.wrap(g))

  /**
   * Create a BidiFlow where the top and bottom flows are just one simple mapping
   * stage each, expressed by the two functions.
   */
  def fromFunctions[I1, O1, I2, O2](top: function.Function[I1, O1], bottom: function.Function[I2, O2]): BidiFlow[I1, O1, I2, O2, Unit] =
    new BidiFlow(scaladsl.BidiFlow(top.apply _, bottom.apply _))

}

class BidiFlow[-I1, +O1, -I2, +O2, +Mat](delegate: scaladsl.BidiFlow[I1, O1, I2, O2, Mat]) extends Graph[BidiShape[I1, O1, I2, O2], Mat] {
  private[stream] override def module = delegate.module
  override def shape = delegate.shape

  def asScala: scaladsl.BidiFlow[I1, O1, I2, O2, Mat] = delegate

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
  def atop[OO1, II2, Mat2](bidi: BidiFlow[O1, OO1, II2, I2, Mat2]): BidiFlow[I1, OO1, II2, O2, Mat] =
    new BidiFlow(delegate.atop(bidi.asScala))

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
  def atop[OO1, II2, Mat2, M](bidi: BidiFlow[O1, OO1, II2, I2, Mat2], combine: function.Function2[Mat, Mat2, M]): BidiFlow[I1, OO1, II2, O2, M] =
    new BidiFlow(delegate.atopMat(bidi.asScala)(combinerToScala(combine)))

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
  def join[Mat2](flow: Flow[O1, I2, Mat2]): Flow[I1, O2, Mat] =
    new Flow(delegate.join(flow.asScala))

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
  def join[Mat2, M](flow: Flow[O1, I2, Mat2], combine: function.Function2[Mat, Mat2, M]): Flow[I1, O2, M] =
    new Flow(delegate.joinMat(flow.asScala)(combinerToScala(combine)))

  /**
   * Turn this BidiFlow around by 180 degrees, logically flipping it upside down in a protocol stack.
   */
  def reversed: BidiFlow[I2, O2, I1, O1, Mat] = new BidiFlow(delegate.reversed)

  override def withAttributes(attr: OperationAttributes): BidiFlow[I1, O1, I2, O2, Mat] =
    new BidiFlow(delegate.withAttributes(attr))
}
