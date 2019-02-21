/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.annotation.ApiMayChange
import akka.japi.{ Pair, Util, function }
import akka.stream._
import akka.event.LoggingAdapter
import akka.util.ConstantFun

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.JavaConverters._
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._

/**
 * A source that provides operations which automatically propagate the context of an element.
 * Only a subset of common operations from [[Source]] is supported. As an escape hatch you can
 * use [[SourceWithContext.via]] to manually provide the context propagation for otherwise unsupported
 * operations.
 *
 * Can be created by calling [[Source.startContextPropagation()]]
 *
 * API MAY CHANGE
 */
@ApiMayChange
final class SourceWithContext[+Out, +Ctx, +Mat](delegate: scaladsl.SourceWithContext[Out, Ctx, Mat]) extends GraphDelegate(delegate) {
  /**
   * Transform this flow by the regular flow. The given flow must support manual context propagation by
   * taking and producing tuples of (data, context).
   *
   * This can be used as an escape hatch for operations that are not (yet) provided with automatic
   * context propagation here.
   *
   * @see [[akka.stream.javadsl.Flow.via]]
   */
  def via[Out2, Ctx2, Mat2](viaFlow: Graph[FlowShape[Pair[Out @uncheckedVariance, Ctx @uncheckedVariance], Pair[Out2, Ctx2]], Mat2]): SourceWithContext[Out2, Ctx2, Mat] =
    viaScala(_.via(akka.stream.scaladsl.Flow[(Out, Ctx)].map { case (o, c) ⇒ Pair(o, c) }.via(viaFlow).map(_.toScala)))

  /**
   * Stops automatic context propagation from here and converts this to a regular
   * stream of a pair of (data, context).
   */
  def endContextPropagation(): Source[Pair[Out @uncheckedVariance, Ctx @uncheckedVariance], Mat @uncheckedVariance] =
    delegate.endContextPropagation.map { case (o, c) ⇒ Pair(o, c) }.asJava

  // remaining operations in alphabetic order

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.collect]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.javadsl.Source.collect]]
   */
  def collect[Out2](pf: PartialFunction[Out, Out2]): SourceWithContext[Out2, Ctx, Mat] =
    viaScala(_.collect(pf))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.filter]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.javadsl.Source.filter]]
   */
  def filter(p: function.Predicate[Out]): SourceWithContext[Out, Ctx, Mat] =
    viaScala(_.filter(p.test))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.filterNot]].
   *
   * Note, that the context of elements that are filtered out is skipped as well.
   *
   * @see [[akka.stream.javadsl.Source.filterNot]]
   */
  def filterNot(p: function.Predicate[Out]): SourceWithContext[Out, Ctx, Mat] =
    viaScala(_.filterNot(p.test))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.grouped]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[akka.stream.javadsl.Source.grouped]]
   */
  def grouped(n: Int): SourceWithContext[java.util.List[Out @uncheckedVariance], java.util.List[Ctx @uncheckedVariance], Mat] =
    viaScala(_.grouped(n).map(_.asJava).mapContext(_.asJava))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.map]].
   *
   * @see [[akka.stream.javadsl.Source.map]]
   */
  def map[Out2](f: function.Function[Out, Out2]): SourceWithContext[Out2, Ctx, Mat] =
    viaScala(_.map(f.apply))

  def mapAsync[Out2](parallelism: Int, f: function.Function[Out, CompletionStage[Out2]]): SourceWithContext[Out2, Ctx, Mat] =
    viaScala(_.mapAsync[Out2](parallelism)(o ⇒ f.apply(o).toScala))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.mapConcat]].
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
   * @see [[akka.stream.javadsl.Source.mapConcat]]
   */
  def mapConcat[Out2](f: function.Function[Out, _ <: java.lang.Iterable[Out2]]): SourceWithContext[Out2, Ctx, Mat] =
    viaScala(_.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  /**
   * Apply the given function to each context element (leaving the data elements unchanged).
   */
  def mapContext[Ctx2](extractContext: function.Function[Ctx, Ctx2]): SourceWithContext[Out, Ctx2, Mat] =
    viaScala(_.mapContext(extractContext.apply))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.sliding]].
   *
   * Each output group will be associated with a `Seq` of corresponding context elements.
   *
   * @see [[akka.stream.javadsl.Source.sliding]]
   */
  def sliding(n: Int, step: Int = 1): SourceWithContext[java.util.List[Out @uncheckedVariance], java.util.List[Ctx @uncheckedVariance], Mat] =
    viaScala(_.sliding(n, step).map(_.asJava).mapContext(_.asJava))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.statefulMapConcat]].
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
   * @see [[akka.stream.javadsl.Source.statefulMapConcat]]
   */
  def statefulMapConcat[Out2](f: function.Creator[function.Function[Out, java.lang.Iterable[Out2]]]): SourceWithContext[Out2, Ctx, Mat] =
    viaScala(_.statefulMapConcat { () ⇒
      val fun = f.create()
      elem ⇒ Util.immutableSeq(fun(elem))
    })

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Source.log]].
   *
   * @see [[akka.stream.javadsl.Source.log]]
   */
  def log(name: String, extract: function.Function[Out, Any], log: LoggingAdapter): SourceWithContext[Out, Ctx, Mat] =
    viaScala(_.log(name, e ⇒ extract.apply(e))(log))

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.log]].
   *
   * @see [[akka.stream.javadsl.Flow.log]]
   */
  def log(name: String, extract: function.Function[Out, Any]): SourceWithContext[Out, Ctx, Mat] =
    this.log(name, extract, null)

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.log]].
   *
   * @see [[akka.stream.javadsl.Flow.log]]
   */
  def log(name: String, log: LoggingAdapter): SourceWithContext[Out, Ctx, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Flow.log]].
   *
   * @see [[akka.stream.javadsl.Flow.log]]
   */
  def log(name: String): SourceWithContext[Out, Ctx, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

  def asScala: scaladsl.SourceWithContext[Out, Ctx, Mat] = delegate

  private[this] def viaScala[Out2, Ctx2, Mat2](f: scaladsl.SourceWithContext[Out, Ctx, Mat] ⇒ scaladsl.SourceWithContext[Out2, Ctx2, Mat2]): SourceWithContext[Out2, Ctx2, Mat2] =
    new SourceWithContext(f(delegate))
}
