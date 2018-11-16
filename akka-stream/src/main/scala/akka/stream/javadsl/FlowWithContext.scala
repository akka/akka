/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.annotation.ApiMayChange
import akka.japi.{ Pair, Util, function }
import akka.stream._
import akka.stream.impl.LinearTraversalBuilder

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.JavaConverters._
import scala.collection.immutable
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
  def fromPairs[CtxIn, In, CtxOut, Out, Mat](under: Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat]) = {
    new FlowWithContext(scaladsl.FlowWithContext.from(scaladsl.Flow[(In, CtxIn)].map { case (i, c) ⇒ Pair(i, c) }.viaMat(under.asScala.map(_.toScala))(scaladsl.Keep.right)))
  }
}

/**
 * API MAY CHANGE
 */
@ApiMayChange
final class FlowWithContext[-CtxIn, -In, +CtxOut, +Out, +Mat](delegate: scaladsl.FlowWithContext[CtxIn, In, CtxOut, Out, Mat]) extends Graph[FlowShape[(In, CtxIn), (Out, CtxOut)], Mat] {
  override val traversalBuilder: LinearTraversalBuilder = delegate.traversalBuilder
  override val shape: FlowShape[(In, CtxIn), (Out, CtxOut)] = delegate.shape
  override def withAttributes(attr: Attributes): FlowWithContext[CtxIn, In, CtxOut, Out, Mat] = new FlowWithContext(delegate.withAttributes(attr))

  def mapContext[CtxOut2](extractContext: function.Function[CtxOut, CtxOut2]): FlowWithContext[CtxIn, In, CtxOut2, Out, Mat] = {
    new FlowWithContext(delegate.mapContext(extractContext.apply))
  }

  def via[CtxOut2, Out2, Mat2](viaFlow: Graph[FlowShape[Pair[Out @uncheckedVariance, CtxOut @uncheckedVariance], Pair[Out2, CtxOut2]], Mat2]): FlowWithContext[CtxIn, In, CtxOut2, Out2, Mat] = {
    val under = endContextPropagation().via(viaFlow)
    FlowWithContext.fromPairs(under)
  }

  def to[Mat2](sink: Graph[SinkShape[Pair[Out @uncheckedVariance, CtxOut @uncheckedVariance]], Mat2]): Sink[Pair[In, CtxIn], Mat] @uncheckedVariance =
    endContextPropagation().toMat(sink, Keep.left)

  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Pair[Out @uncheckedVariance, CtxOut @uncheckedVariance]], Mat2], combine: function.Function2[Mat, Mat2, Mat3]): Sink[Pair[In, CtxIn], Mat3] @uncheckedVariance =
    endContextPropagation().toMat(sink, combine)

  def endContextPropagation(): Flow[Pair[In, CtxIn], Pair[Out, CtxOut], Mat] @uncheckedVariance =
    scaladsl.Flow[Pair[In, CtxIn]]
      .map(_.toScala)
      .viaMat(delegate.endContextPropagation)(scaladsl.Keep.right)
      .map { case (o, c) ⇒ Pair(o, c) }
      .asJava

  def map[Out2](f: function.Function[Out, Out2]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    new FlowWithContext(delegate.map(f.apply))

  def mapAsync[Out2](parallelism: Int, f: function.Function[Out, CompletionStage[Out2]]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    new FlowWithContext(delegate.mapAsync[Out2](parallelism)(o ⇒ f.apply(o).toScala))

  def collect[Out2](pf: PartialFunction[Out, Out2]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    new FlowWithContext(delegate.collect(pf))

  def filter(p: function.Predicate[Out]): FlowWithContext[CtxIn, In, CtxOut, Out, Mat] =
    new FlowWithContext(delegate.filter(p.test))

  def filterNot(p: function.Predicate[Out]): FlowWithContext[CtxIn, In, CtxOut, Out, Mat] =
    new FlowWithContext(delegate.filterNot(p.test))

  def grouped(n: Int): FlowWithContext[CtxIn, In, java.util.List[CtxOut @uncheckedVariance], java.util.List[Out @uncheckedVariance], Mat] = {
    val f = new function.Function[immutable.Seq[CtxOut], java.util.List[CtxOut]] {
      def apply(ctxs: immutable.Seq[CtxOut]) = ctxs.asJava
    }
    new FlowWithContext(delegate.grouped(n).map(_.asJava)).mapContext(f)
  }

  def mapConcat[Out2](f: function.Function[Out, _ <: java.lang.Iterable[Out2]]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    new FlowWithContext(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  def statefulMapConcat[Out2](f: function.Creator[function.Function[Out, java.lang.Iterable[Out2]]]): FlowWithContext[CtxIn, In, CtxOut, Out2, Mat] =
    new FlowWithContext(delegate.statefulMapConcat { () ⇒
      val fun = f.create()
      elem ⇒ Util.immutableSeq(fun(elem))
    })

  def sliding(n: Int, step: Int = 1): FlowWithContext[CtxIn, In, java.util.List[CtxOut @uncheckedVariance], java.util.List[Out @uncheckedVariance], Mat] = {
    val f = new function.Function[immutable.Seq[CtxOut], java.util.List[CtxOut]] {
      def apply(ctxs: immutable.Seq[CtxOut]) = ctxs.asJava
    }
    new FlowWithContext(delegate.sliding(n, step).map(_.asJava)).mapContext(f)
  }

  def asScala = delegate
}
