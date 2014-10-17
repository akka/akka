/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._

import java.util

import akka.japi.Util
import akka.japi.Pair
import akka.stream.javadsl.japi.Function

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

object Flow {

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](): javadsl.Flow[T, T] = new javadsl.FlowAdapter[T, T](scaladsl2.Pipe.empty[T])

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](clazz: Class[T]): javadsl.Flow[T, T] = of[T]()

}

/** Java API */
abstract class Flow[-In, +Out] extends FlowOps[In, Out] {

  /**
   * Transform this flow by appending the given processing steps.
   */
  def connect[T](flow: javadsl.Flow[Out, T]): javadsl.Flow[In, T]

  /**
   * Connect this flow to a sink, concatenating the processing steps of both.
   */
  def connect(sink: javadsl.Sink[Out]): javadsl.Sink[In]
}

/**
 * INTERNAL API
 */
private[akka] class FlowAdapter[-In, +Out](delegate: scaladsl2.Flow[In, Out]) extends javadsl.Flow[In, Out] {

  import scala.collection.JavaConverters._
  import akka.stream.scaladsl2.JavaConverters._

  /** Converts this Flow to it's Scala DSL counterpart */
  def asScala: scaladsl2.Flow[In, Out] = delegate

  // FLOW //

  /**
   * Transform this flow by appending the given processing steps.
   */
  override def connect[T](flow: javadsl.Flow[Out, T]): javadsl.Flow[In, T] =
    new FlowAdapter(delegate.connect(flow.asScala))

  /**
   * Connect this flow to a sink, concatenating the processing steps of both.
   */
  override def connect(sink: javadsl.Sink[Out]): javadsl.Sink[In] =
    SinkAdapter(delegate.connect(sink.asScala))

  // RUN WITH //

  def runWith[T, D](source: javadsl.KeyedSource[In, T], sink: javadsl.KeyedSink[Out, D], materializer: scaladsl2.FlowMaterializer): akka.japi.Pair[T, D] = {
    val p = delegate.runWith(source.asScala, sink.asScala)(materializer)
    akka.japi.Pair(p._1.asInstanceOf[T], p._2.asInstanceOf[D])
  }

  def runWith[D](source: javadsl.SimpleSource[In], sink: javadsl.KeyedSink[Out, D], materializer: scaladsl2.FlowMaterializer): D =
    delegate.runWith(source.asScala, sink.asScala)(materializer).asInstanceOf[D]

  def runWith[T](source: javadsl.KeyedSource[In, T], sink: javadsl.SimpleSink[Out], materializer: scaladsl2.FlowMaterializer): T =
    delegate.runWith(source.asScala, sink.asScala)(materializer).asInstanceOf[T]

  def runWith(source: javadsl.SimpleSource[In], sink: javadsl.SimpleSink[Out], materializer: scaladsl2.FlowMaterializer): Unit =
    delegate.runWith(source.asScala, sink.asScala)(materializer)

  // COMMON OPS //

  override def map[T](f: Function[Out, T]): javadsl.Flow[In, T] =
    new FlowAdapter(delegate.map(f.apply))

  override def mapConcat[U](f: Function[Out, java.util.List[U]]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  override def mapAsync[U](f: Function[Out, Future[U]]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.mapAsync(f.apply))

  override def mapAsyncUnordered[T](f: Function[Out, Future[T]]): javadsl.Flow[In, T] =
    new FlowAdapter(delegate.mapAsyncUnordered(f.apply))

  override def filter(p: japi.Predicate[Out]): javadsl.Flow[In, Out] =
    new FlowAdapter(delegate.filter(p.test))

  override def collect[U](pf: PartialFunction[Out, U]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.collect(pf))

  override def grouped(n: Int): javadsl.Flow[In, java.util.List[Out @uncheckedVariance]] =
    new FlowAdapter(delegate.grouped(n).map(_.asJava)).asInstanceOf[javadsl.Flow[In, java.util.List[Out @uncheckedVariance]]] // FIXME optimize to one step

  override def groupedWithin(n: Int, d: FiniteDuration): javadsl.Flow[In, util.List[Out @uncheckedVariance]] =
    new FlowAdapter(delegate.groupedWithin(n, d).map(_.asJava)) // FIXME optimize to one step

  override def drop(n: Int): javadsl.Flow[In, Out] =
    new FlowAdapter(delegate.drop(n))

  override def dropWithin(d: FiniteDuration): javadsl.Flow[In, Out] =
    new FlowAdapter(delegate.dropWithin(d))

  override def take(n: Int): Flow[In, Out] =
    new FlowAdapter(delegate.take(n))

  override def takeWithin(d: FiniteDuration): Flow[In, Out] =
    new FlowAdapter(delegate.takeWithin(d))

  override def transform[U](name: String, transformer: japi.Creator[Transformer[Out, U]]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.transform(name, () ⇒ transformer.create()))

  override def timerTransform[U](name: String, transformer: japi.Creator[TimerTransformer[Out, U]]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.timerTransform(name, () ⇒ transformer.create()))

  override def prefixAndTail(n: Int): javadsl.Flow[In, akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance]]] =
    new FlowAdapter(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ akka.japi.Pair(taken.asJava, tail.asJava) })

  override def groupBy[K](f: Function[Out, K]): javadsl.Flow[In, akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance]]] =
    new FlowAdapter(delegate.groupBy(f.apply).map { case (k, p) ⇒ akka.japi.Pair(k, p.asJava) }) // FIXME optimize to one step

  override def splitWhen(p: japi.Predicate[Out]): javadsl.Flow[In, javadsl.Source[Out]] =
    new FlowAdapter(delegate.splitWhen(p.test).map(_.asJava))

  override def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.flatten(strategy))

  override def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out] =
    new FlowAdapter(delegate.buffer(size, overflowStrategy))

  override def expand[S, U](seed: japi.Function[Out, S], extrapolate: japi.Function[S, akka.japi.Pair[U, S]]): javadsl.Flow[In, U] =
    new FlowAdapter(delegate.expand(seed.apply, (s: S) ⇒ {
      val p = extrapolate.apply(s)
      (p.first, p.second)
    }))

  override def conflate[S](seed: Function[Out, S], aggregate: japi.Function2[S, Out, S]): javadsl.Flow[In, S] =
    new FlowAdapter(delegate.conflate(seed.apply, aggregate.apply))

}

/**
 * Java API
 *
 * Flow with attached input and output, can be executed.
 */
trait RunnableFlow {
  def run(materializer: scaladsl2.FlowMaterializer): javadsl.MaterializedMap
}

/** INTERNAL API */
private[akka] class RunnableFlowAdapter(runnable: scaladsl2.RunnableFlow) extends RunnableFlow {
  override def run(materializer: scaladsl2.FlowMaterializer): MaterializedMap = new MaterializedMapAdapter(runnable.run()(materializer))
}
