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
import akka.util.ConstantFun
import akka.event.LoggingAdapter

/**
 * Shared stream operations for [[FlowWithContext]] and [[SourceWithContext]] that automatically propagate a context
 * element with each data element.
 *
 * API MAY CHANGE
 */
@ApiMayChange
trait FlowWithContextOps[+Out, +Ctx, +Mat] {
  type ReprMat[+O, +C, +M] <: FlowWithContextOps[O, C, M] {
    type ReprMat[+OO, +CC, +MatMat] = FlowWithContextOps.this.ReprMat[OO, CC, MatMat]
  }
  type Repr[+O, +C] = ReprMat[O, C, Mat @uncheckedVariance]

  /**
   * Transform this flow by the regular flow. The given flow must support manual context propagation by
   * taking and producing tuples of (data, context).
   *
   * This can be used as an escape hatch for operations that are not (yet) provided with automatic
   * context propagation here.
   *
   * @see [[akka.stream.scaladsl.FlowOps.via]]
   */
  def via[Out2, Ctx2, Mat2](flow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2]): Repr[Out2, Ctx2]

  /**
   * Transform this flow by the regular flow. The given flow must support manual context propagation by
   * taking and producing tuples of (data, context).
   *
   * This can be used as an escape hatch for operations that are not (yet) provided with automatic
   * context propagation here.
   *
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting Flow.
   *
   * @see [[akka.stream.scaladsl.FlowOps.viaMat]]
   */
  def viaMat[Out2, Ctx2, Mat2, Mat3](flow: Graph[FlowShape[(Out, Ctx), (Out2, Ctx2)], Mat2])(
      combine: (Mat, Mat2) => Mat3): ReprMat[Out2, Ctx2, Mat3]

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.map]].
   *
   * @see [[akka.stream.scaladsl.FlowOps.map]]
   */
  def map[Out2](f: Out => Out2): Repr[Out2, Ctx] =
    via(flow.map { case (e, ctx) => (f(e), ctx) })

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.mapAsync]].
   *
   * @see [[akka.stream.scaladsl.FlowOps.mapAsync]]
   */
  def mapAsync[Out2](parallelism: Int)(f: Out => Future[Out2]): Repr[Out2, Ctx] =
    via(flow.mapAsync(parallelism) {
      case (e, ctx) => f(e).map(o => (o, ctx))(ExecutionContexts.sameThreadExecutionContext)
    })

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.collect]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.scaladsl.FlowOps.collect]]
   */
  def collect[Out2](f: PartialFunction[Out, Out2]): Repr[Out2, Ctx] =
    via(flow.collect {
      case (e, ctx) if f.isDefinedAt(e) => (f(e), ctx)
    })

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.filter]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.scaladsl.FlowOps.filter]]
   */
  def filter(pred: Out => Boolean): Repr[Out, Ctx] =
    collect { case e if pred(e) => e }

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.filterNot]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.scaladsl.FlowOps.filterNot]]
   */
  def filterNot(pred: Out => Boolean): Repr[Out, Ctx] =
    collect { case e if !pred(e) => e }

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.grouped]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[akka.stream.scaladsl.FlowOps.grouped]]
   */
  def grouped(n: Int): Repr[immutable.Seq[Out], immutable.Seq[Ctx]] =
    via(flow.grouped(n).map { elsWithContext =>
      val (els, ctxs) = elsWithContext.unzip
      (els, ctxs)
    })

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.sliding]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[akka.stream.scaladsl.FlowOps.sliding]]
   */
  def sliding(n: Int, step: Int = 1): Repr[immutable.Seq[Out], immutable.Seq[Ctx]] =
    via(flow.sliding(n, step).map { elsWithContext =>
      val (els, ctxs) = elsWithContext.unzip
      (els, ctxs)
    })

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.mapConcat]].
   *
   * The context of the input element will be associated with each of the output elements calculated from
   * this input element.
   *
   * Example:
   *
   * ```
   * def dup(element: String) = Seq(element, element)
   *
   * Input:
   *
   * ("a", 1)
   * ("b", 2)
   *
   * inputElements.mapConcat(dup)
   *
   * Output:
   *
   * ("a", 1)
   * ("a", 1)
   * ("b", 2)
   * ("b", 2)
   * ```
   *
   * @see [[akka.stream.scaladsl.FlowOps.mapConcat]]
   */
  def mapConcat[Out2](f: Out => immutable.Iterable[Out2]): Repr[Out2, Ctx] =
    via(flow.mapConcat {
      case (e, ctx) => f(e).map(_ -> ctx)
    })

  /**
   * Apply the given function to each context element (leaving the data elements unchanged).
   */
  def mapContext[Ctx2](f: Ctx => Ctx2): Repr[Out, Ctx2] =
    via(flow.map { case (e, ctx) => (e, f(ctx)) })

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.FlowOps.log]].
   *
   * @see [[akka.stream.scaladsl.FlowOps.log]]
   */
  def log(name: String, extract: Out => Any = ConstantFun.scalaIdentityFunction)(
      implicit log: LoggingAdapter = null): Repr[Out, Ctx] = {
    val extractWithContext: ((Out, Ctx)) => Any = { case (e, _) => extract(e) }
    via(flow.log(name, extractWithContext)(log))
  }

  private[akka] def flow[T, C]: Flow[(T, C), (T, C), NotUsed] = Flow[(T, C)]
}
