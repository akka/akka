/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ FlowShape, Graph, SinkShape }

import scala.annotation.unchecked.uncheckedVariance

trait FlowWithContextOpsMat[+Out, +Ctx, +Mat] extends FlowWithContextOps[Out, Ctx, Mat] {
  type Repr[+O, +C] <: ReprMat[O, C, Mat] {
    type Repr[+OO, +CC] = FlowWithContextOpsMat.this.Repr[OO, CC]
    type ReprMat[+OO, +CC, +MM] = FlowWithContextOpsMat.this.ReprMat[OO, CC, MM]
    type Closed = FlowWithContextOpsMat.this.Closed
    type ClosedMat[+M] = FlowWithContextOpsMat.this.ClosedMat[M]
  }
  type ReprMat[+O, +C, +M] <: FlowWithContextOpsMat[O, C, M] {
    type Repr[+OO, +CC] = FlowWithContextOpsMat.this.ReprMat[OO, CC, M @uncheckedVariance]
    type ReprMat[+OO, +CC, +MM] = FlowWithContextOpsMat.this.ReprMat[OO, CC, MM]
    type Closed = FlowWithContextOpsMat.this.ClosedMat[M @uncheckedVariance]
    type ClosedMat[+MM] = FlowWithContextOpsMat.this.ClosedMat[MM]
  }
  type ClosedMat[+M] <: Graph[_, M]

  def to[Mat2](sink: Graph[SinkShape[(Out, Ctx)], Mat2]): Closed

  def alsoTo(that: Graph[SinkShape[(Out, Ctx)], _]): Repr[Out, Ctx] = via(alsoToGraph(that))

  def toMat[Mat2, Mat3](sink: Graph[SinkShape[(Out, Ctx)], Mat2])(combine: (Mat, Mat2) => Mat3): ClosedMat[Mat3]

  /**
   * Transform this flow by the regular flow. The given flow must support manual context propagation by
   * taking and producing tuples of (data, context).
   *
   *  It is up to the implementer to ensure the inner flow does not exhibit any behaviour that is not expected
   *  by the downstream elements, such as reordering. For more background on these requirements
   *  see https://doc.akka.io/docs/akka/current/stream/stream-context.html.
   *
   * This can be used as an escape hatch for operations that are not (yet) provided with automatic
   * context propagation here.
   *
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting Flow.
   *
   * @see [[akka.stream.scaladsl.FlowOpsMat.viaMat]]
   */
  def viaMat[Out2, Ctx2, Mat2, Mat3](flow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2])(
      combine: (Mat, Mat2) => Mat3): ReprMat[Out2, Ctx2, Mat3]

  protected def alsoToGraph[M](that: Graph[SinkShape[(Out, Ctx)], M])
      : Graph[FlowShape[(Out @uncheckedVariance, Ctx @uncheckedVariance), (Out, Ctx)], M] =
    GraphDSL.createGraph(that) { implicit b => r =>
      import GraphDSL.Implicits._
      val bcast = b.add(Broadcast[(Out, Ctx)](2, eagerCancel = true))
      bcast.out(1) ~> r
      FlowShape(bcast.in, bcast.out(0))
    }

}
