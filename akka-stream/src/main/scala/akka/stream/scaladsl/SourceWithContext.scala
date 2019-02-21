/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.annotation.unchecked.uncheckedVariance

import akka.annotation.ApiMayChange
import akka.stream._

/**
 * A source that provides operations which automatically propagate the context of an element.
 * Only a subset of common operations from [[FlowOps]] is supported. As an escape hatch you can
 * use [[FlowWithContextOps.via]] to manually provide the context propagation for otherwise unsupported
 * operations.
 *
 * Can be created by calling [[Source.startContextPropagation()]]
 *
 * API MAY CHANGE
 */
@ApiMayChange
final class SourceWithContext[+Out, +Ctx, +Mat] private[stream] (
  delegate: Source[(Out, Ctx), Mat]
) extends GraphDelegate(delegate) with FlowWithContextOps[Out, Ctx, Mat] {
  override type ReprMat[+O, +C, +M] = SourceWithContext[O, C, M @uncheckedVariance]

  override def via[Out2, Ctx2, Mat2](viaFlow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2]): Repr[Out2, Ctx2] =
    new SourceWithContext(delegate.via(viaFlow))

  override def viaMat[Out2, Ctx2, Mat2, Mat3](flow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): SourceWithContext[Out2, Ctx2, Mat3] =
    new SourceWithContext(delegate.viaMat(flow)(combine))

  /**
   * Stops automatic context propagation from here and converts this to a regular
   * stream of a pair of (data, context).
   */
  def endContextPropagation: Source[(Out, Ctx), Mat] = delegate

  def asJava[JOut >: Out, JCtx >: Ctx, JMat >: Mat]: javadsl.SourceWithContext[JOut, JCtx, JMat] =
    new javadsl.SourceWithContext(this)
}

