/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.annotation.ApiMayChange
import akka.japi.{ Pair, Util, function }
import akka.stream._

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.JavaConverters._
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._

/**
 * API MAY CHANGE
 */
@ApiMayChange
object FlowWithContext {
  def create[Ctx, In](): FlowWithContext[Ctx, In, Ctx, In, akka.NotUsed] = {
    new FlowWithContext(scaladsl.FlowWithContext[Ctx, In])
  }
  def fromPairs[CtxIn, In, CtxOut, Out, Mat](under: Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat]): FlowWithContext[CtxIn, In, CtxOut, Out, Mat] = {
    new FlowWithContext(scaladsl.FlowWithContext.from(scaladsl.Flow[(In, CtxIn)].map { case (i, c) ⇒ Pair(i, c) }.viaMat(under.asScala.map(_.toScala))(scaladsl.Keep.right)))
  }
}

/**
 * A flow that provides operations which automatically propagate the context of an element.
 * Only a subset of common operations from [[Flow]] is supported. As an escape hatch you can
 * use [[FlowWithContext.via]] to manually provide the context propagation for otherwise unsupported
 * operations.
 *
 * An "empty" flow can be created by calling `FlowWithContext[Ctx, T]`.
 *
 * API MAY CHANGE
 */
@ApiMayChange
final class FlowWithContext[-CtxIn, -In, +CtxOut, +Out, +Mat](delegate: scaladsl.FlowWithContext[CtxIn, In, CtxOut, Out, Mat]) extends GraphDelegate(delegate) {
  /**
   * Transform this flow by the regular flow. The given flow must support manual context propagation by
   * taking and producing tuples of (data, context).
   *
   * This can be used as an escape hatch for operations that are not (yet) provided with automatic
   * context propagation here.
   *
   * @see [[akka.stream.javadsl.Flow.via]]
   */
  def via[CtxOut2, Out2, Mat2](viaFlow: Graph[FlowShape[Pair[Out @uncheckedVariance, CtxOut @uncheckedVariance], Pair[Out2, CtxOut2]], Mat2]): FlowWithContext[CtxIn, In, CtxOut2, Out2, Mat] = {
    val under = asFlow().via(viaFlow)
    FlowWithContext.fromPairs(under)
  }

  /**
   * Creates a regular flow of pairs (data, context).
   */
  def asFlow(): Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat] @uncheckedVariance =
    scaladsl.Flow[Pair[In, CtxIn]]
      .map(_.toScala)
      .viaMat(delegate.asFlow)(scaladsl.Keep.right)
      .map { case (o, c) ⇒ Pair(o, c) }
      .asJava

  // remaining operations in alphabetic order

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.collect]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.javadsl.Flow.collect]]
   */
  def collect[Out2](pf: PartialFunction[Out, Out2]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    viaScala(_.collect(pf))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.filter]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.javadsl.Flow.filter]]
   */
  def filter(p: function.Predicate[Out]): FlowWithContext[CtxIn, In, CtxOut, Out, Mat] =
    viaScala(_.filter(p.test))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.filterNot]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.javadsl.Flow.filterNot]]
   */
  def filterNot(p: function.Predicate[Out]): FlowWithContext[CtxIn, In, CtxOut, Out, Mat] =
    viaScala(_.filterNot(p.test))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.grouped]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[akka.stream.javadsl.Flow.grouped]]
   */
  def grouped(n: Int): FlowWithContext[CtxIn, In, java.util.List[CtxOut @uncheckedVariance], java.util.List[Out @uncheckedVariance], Mat] =
    viaScala(_.grouped(n).map(_.asJava).mapContext(_.asJava))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.map]].
   *
   * @see [[akka.stream.javadsl.Flow.map]]
   */
  def map[Out2](f: function.Function[Out, Out2]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    viaScala(_.map(f.apply))

  def mapAsync[Out2](parallelism: Int, f: function.Function[Out, CompletionStage[Out2]]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    viaScala(_.mapAsync[Out2](parallelism)(o ⇒ f.apply(o).toScala))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.mapConcat]].
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
   * @see [[akka.stream.javadsl.Flow.mapConcat]]
   */
  def mapConcat[Out2](f: function.Function[Out, _ <: java.lang.Iterable[Out2]]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    viaScala(_.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  /**
   * Apply the given function to each context element (leaving the data elements unchanged).
   */
  def mapContext[CtxOut2](extractContext: function.Function[CtxOut, CtxOut2]): FlowWithContext[CtxIn, In, CtxOut2, Out, Mat] = {
    viaScala(_.mapContext(extractContext.apply))
  }

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.sliding]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[akka.stream.javadsl.Flow.sliding]]
   */
  def sliding(n: Int, step: Int = 1): FlowWithContext[CtxIn, In, java.util.List[CtxOut @uncheckedVariance], java.util.List[Out @uncheckedVariance], Mat] =
    viaScala(_.sliding(n, step).map(_.asJava).mapContext(_.asJava))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.statefulMapConcat]].
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
   * inputElements.statefulMapConcat(() => dup)
   *
   * Output:
   *
   * ("a", 1)
   * ("a", 1)
   * ("b", 2)
   * ("b", 2)
   * ```
   *
   * @see [[akka.stream.javadsl.Flow.statefulMapConcat]]
   */
  def statefulMapConcat[Out2](f: function.Creator[function.Function[Out, java.lang.Iterable[Out2]]]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    viaScala(_.statefulMapConcat { () ⇒
      val fun = f.create()
      elem ⇒ Util.immutableSeq(fun(elem))
    })

  def asScala: scaladsl.FlowWithContext[CtxIn, In, CtxOut, Out, Mat] = delegate

  private[this] def viaScala[CtxIn2, In2, CtxOut2, Out2, Mat2](f: scaladsl.FlowWithContext[CtxIn, In, CtxOut, Out, Mat] ⇒ scaladsl.FlowWithContext[CtxIn2, In2, CtxOut2, Out2, Mat2]): FlowWithContext[CtxIn2, In2, CtxOut2, Out2, Mat2] =
    new FlowWithContext(f(delegate))
}
