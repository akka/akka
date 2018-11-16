/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.annotation.unchecked.uncheckedVariance

import akka.annotation.ApiMayChange
import akka.stream._
import akka.stream.impl.LinearTraversalBuilder

/**
 * API MAY CHANGE
 */
@ApiMayChange
object SourceWithContext {
  def apply[Out, Mat](underlying: Source[Out, Mat]): SourceWithContext[Out, Out, Mat] = {
    val under = underlying.map(e ⇒ (e, e))
    new SourceWithContext[Out, Out, Mat](under, under.traversalBuilder, under.shape)
  }
  def from[Out, Ctx, Mat](under: Source[(Out, Ctx), Mat]): SourceWithContext[Ctx, Out, Mat] = {
    new SourceWithContext[Ctx, Out, Mat](under, under.traversalBuilder, under.shape)
  }
}

/**
 * API MAY CHANGE
 */
@ApiMayChange
final class SourceWithContext[+Ctx, +Out, +Mat](
  underlying:                    Source[(Out, Ctx), Mat],
  override val traversalBuilder: LinearTraversalBuilder,
  override val shape:            SourceShape[(Out, Ctx)]
) extends FlowWithContextOps[Ctx, Out, Mat] with Graph[SourceShape[(Out, Ctx)], Mat] {

  override def withAttributes(attr: Attributes): Repr[Ctx, Out] = new SourceWithContext(underlying, traversalBuilder.setAttributes(attr), shape)

  override type Repr[+C, +O] = SourceWithContext[C, O, Mat @uncheckedVariance]
  override type Prov[+C, +O] = Source[(O, C), Mat @uncheckedVariance]

  override def via[Ctx2, Out2, Mat2](viaFlow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2]): Repr[Ctx2, Out2] = {
    val under = underlying.via(viaFlow)
    new SourceWithContext[Ctx2, Out2, Mat](under, under.traversalBuilder, under.shape)
  }

  def to[Mat2](sink: Graph[SinkShape[(Out, Ctx)], Mat2]): RunnableGraph[Mat] = underlying.toMat(sink)(Keep.left)

  def toMat[Mat2, Mat3](sink: Graph[SinkShape[(Out, Ctx)], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): RunnableGraph[Mat3] =
    underlying.toMat(sink)(combine)

  override def endContextPropagation: Prov[Ctx, Out] = underlying

  def asJava[JCtx >: Ctx, JOut >: Out, JMat >: Mat]: javadsl.SourceWithContext[JCtx, JOut, JMat] =
    new javadsl.SourceWithContext(this)
}

