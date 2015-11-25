/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream._
import akka.stream.impl.Stages.StageModule
import language.higherKinds
import scala.annotation.unchecked.uncheckedVariance

/**
 * A “stream of streams” sub-flow of data elements, e.g. produced by `groupBy`.
 * SubFlows cannot contribute to the super-flow’s materialized value since they
 * are materialized later, during the runtime of the flow graph processing.
 */
trait SubFlow[+Out, +Mat, +F[+_], C] extends FlowOps[Out, Mat] {

  override type Repr[+T] = SubFlow[T, Mat @uncheckedVariance, F @uncheckedVariance, C @uncheckedVariance]
  override type Closed = C

  /**
   * Attach a [[Sink]] to each sub-flow, closing the overall Graph that is being
   * constructed.
   */
  def to[M](sink: Graph[SinkShape[Out], M]): C

  /**
   * Flatten the sub-flows back into the super-flow by performing a merge.
   */
  def mergeSubstreams: F[Out]

  /**
   * Flatten the sub-flows back into the super-flow by concatenating them.
   * This is usually a bad idea when combined with `groupBy` since it can
   * easily lead to deadlock—the concatenation does not consume from the second
   * substream until the first has finished and the `groupBy` stage will get
   * back-pressure from the second stream.
   */
  def concatSubstreams: F[Out]
}
