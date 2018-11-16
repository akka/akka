/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.Future
import scala.language.higherKinds
import scala.annotation.unchecked.uncheckedVariance

import akka.NotUsed
import akka.annotation.ApiMayChange
import akka.dispatch.ExecutionContexts
import akka.stream._
import akka.stream.impl.LinearTraversalBuilder

/**
 * API MAY CHANGE
 */
@ApiMayChange
trait FlowWithContextOps[+Ctx, +Out, +Mat] {
  type Repr[+C, +O] <: FlowWithContextOps[C, O, Mat] {
    type Repr[+CC, +OO] = FlowWithContextOps.this.Repr[CC, OO]
    type Prov[+CC, +OO] = FlowWithContextOps.this.Prov[CC, OO]
  }

  type Prov[+C, +O] <: FlowOpsMat[(O, C), Mat]

  def via[Ctx2, Out2, Mat2](flow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2]): Repr[Ctx2, Out2]

  def map[Out2](f: Out ⇒ Out2): Repr[Ctx, Out2] =
    via(flow.map { case (e, ctx) ⇒ (f(e), ctx) })

  def mapAsync[Out2](parallelism: Int)(f: Out ⇒ Future[Out2]): Repr[Ctx, Out2] =
    via(flow.mapAsync(parallelism) { case (e, ctx) ⇒ f(e).map(o ⇒ (o, ctx))(ExecutionContexts.sameThreadExecutionContext) })

  def collect[Out2](f: PartialFunction[Out, Out2]): Repr[Ctx, Out2] =
    via(flow.collect {
      case (e, ctx) if f.isDefinedAt(e) ⇒ (f(e), ctx)
    })

  def filter(pred: Out ⇒ Boolean): Repr[Ctx, Out] =
    collect { case e if pred(e) ⇒ e }

  def filterNot(pred: Out ⇒ Boolean): Repr[Ctx, Out] =
    collect { case e if !pred(e) ⇒ e }

  def grouped(n: Int): Repr[immutable.Seq[Ctx], immutable.Seq[Out]] =
    via(flow.grouped(n).map { elsWithContext ⇒
      val (els, ctxs) = elsWithContext.unzip
      (els, ctxs)
    })

  def sliding(n: Int, step: Int = 1): Repr[immutable.Seq[Ctx], immutable.Seq[Out]] =
    via(flow.sliding(n, step).map { elsWithContext ⇒
      val (els, ctxs) = elsWithContext.unzip
      (els, ctxs)
    })

  def mapConcat[Out2](f: Out ⇒ immutable.Iterable[Out2]): Repr[Ctx, Out2] = statefulMapConcat(() ⇒ f)

  def statefulMapConcat[Out2](f: () ⇒ Out ⇒ immutable.Iterable[Out2]): Repr[Ctx, Out2] = {
    val fCtx: () ⇒ ((Out, Ctx)) ⇒ immutable.Iterable[(Out2, Ctx)] = { () ⇒ elWithContext ⇒
      val (el, ctx) = elWithContext
      f()(el).map(o ⇒ (o, ctx))
    }
    via(flow.statefulMapConcat(fCtx))
  }

  def mapContext[Ctx2](f: Ctx ⇒ Ctx2): Repr[Ctx2, Out] =
    via(flow.map { case (e, ctx) ⇒ (e, f(ctx)) })

  def endContextPropagation: Prov[Ctx, Out]

  private[akka] def flow[T, C]: Flow[(T, C), (T, C), NotUsed] = Flow[(T, C)]
}

/**
 * API MAY CHANGE
 */
@ApiMayChange
object FlowWithContext {
  def apply[Ctx, In]: FlowWithContext[Ctx, In, Ctx, In, akka.NotUsed] = {
    val under = Flow[(In, Ctx)]
    new FlowWithContext[Ctx, In, Ctx, In, akka.NotUsed](under, under.traversalBuilder, under.shape)
  }
  def from[CI, I, CO, O, M](flow: Flow[(I, CI), (O, CO), M]) = new FlowWithContext(flow, flow.traversalBuilder, flow.shape)
}

/**
 * API MAY CHANGE
 */
@ApiMayChange
final class FlowWithContext[-CtxIn, -In, +CtxOut, +Out, +Mat](
  underlying:                    Flow[(In, CtxIn), (Out, CtxOut), Mat],
  override val traversalBuilder: LinearTraversalBuilder,
  override val shape:            FlowShape[(In, CtxIn), (Out, CtxOut)]
) extends FlowWithContextOps[CtxOut, Out, Mat] with Graph[FlowShape[(In, CtxIn), (Out, CtxOut)], Mat] {

  override def withAttributes(attr: Attributes): Repr[CtxOut, Out] = new FlowWithContext(underlying, traversalBuilder.setAttributes(attr), shape)

  override type Repr[+C, +O] = FlowWithContext[CtxIn @uncheckedVariance, In @uncheckedVariance, C, O, Mat @uncheckedVariance]
  override type Prov[+C, +O] = Flow[(In @uncheckedVariance, CtxIn @uncheckedVariance), (O, C), Mat @uncheckedVariance]

  override def via[Ctx2, Out2, Mat2](viaFlow: Graph[FlowShape[(Out, CtxOut), (Out2, Ctx2)], Mat2]): Repr[Ctx2, Out2] = from(underlying.via(viaFlow))

  def to[Mat2](sink: Graph[SinkShape[(Out, CtxOut)], Mat2]): Sink[(In, CtxIn), Mat] = underlying.toMat(sink)(Keep.left)

  def toMat[Mat2, Mat3](sink: Graph[SinkShape[(Out, CtxOut)], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Sink[(In, CtxIn), Mat3] = underlying.toMat(sink)(combine)

  override def endContextPropagation: Prov[CtxOut, Out] = underlying

  private[this] def from[CI, I, CO, O, M](flow: Flow[(I, CI), (O, CO), M]) = FlowWithContext.from(flow)

  def asJava[JCtxIn <: CtxIn, JIn <: In, JCtxOut >: CtxOut, JOut >: Out, JMat >: Mat]: javadsl.FlowWithContext[JCtxIn, JIn, JCtxOut, JOut, JMat] =
    new javadsl.FlowWithContext(this)
}

