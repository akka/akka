/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._

import java.util
import java.util.concurrent.Callable

import akka.japi.Util
import akka.stream.javadsl.japi.{ Predicate, Function2, Creator, Function }
import akka.stream.scaladsl2._
import org.reactivestreams.{ Subscriber, Publisher }
import scaladsl2.FlowMaterializer
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import scala.language.higherKinds
import scala.language.implicitConversions

/**
 * Java API
 *
 * A `Source` is a set of stream processing steps that has one open output and an attached input.
 * Can be used as a `Publisher`
 */
abstract class Source[+Out] extends javadsl.SourceOps[Out] {

  /**
   * Transform this source by appending the given processing stages.
   */
  def connect[T](flow: javadsl.Flow[Out, T]): javadsl.Source[T]

  /**
   * Connect this source to a sink, concatenating the processing steps of both.
   */
  def connect(sink: javadsl.Sink[Out]): javadsl.RunnableFlow

  /**
   * Connect this `Source` to a `Drain` and run it. The returned value is the materialized value
   * of the `Drain`, e.g. the `Publisher` of a [[akka.stream.scaladsl2.PublisherDrain]].
   *
   * @tparam D materialized type of the given Drain
   */
  def runWith[D](drain: DrainWithKey[Out, D], materializer: FlowMaterializer): D

  /**
   * Connect this `Source` to a `Drain` and run it. The returned value is the materialized value
   * of the `Drain`, e.g. the `Publisher` of a [[akka.stream.scaladsl2.PublisherDrain]].
   */
  def runWith(drain: SimpleDrain[Out], materializer: FlowMaterializer): Unit

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U](zero: U, f: japi.Function2[U, Out, U], materializer: FlowMaterializer): Future[U]

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   */
  def concat[Out2 >: Out](second: Source[Out2]): Source[Out2]

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream.
   */
  def foreach(f: japi.Procedure[Out], materializer: FlowMaterializer): Future[Unit]
}

object Source {

  /**
   * Java API
   *
   * Adapt [[scaladsl2.Source]] for use within JavaDSL
   */
  def adapt[O](source: scaladsl2.Source[O]): Source[O] = SourceAdapter(source)

  /**
   * Java API
   * Adapt [[scaladsl2.SourcePipe]] for use within JavaDSL
   */
  def adapt[O](source: scaladsl2.SourcePipe[O]): Source[O] = SourceAdapter(source)

  /**
   * Java API
   *
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def from[O](publisher: Publisher[O]): javadsl.Source[O] =
    SourceAdapter(scaladsl2.Source.apply(publisher))

  /**
   * Java API
   *
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source(Seq(1,2,3).iterator)`
   *
   * Start a new `Source` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def from[O](iterator: java.util.Iterator[O]): javadsl.Source[O] =
    SourceAdapter(scaladsl2.IteratorTap(iterator.asScala))

  /**
   * Java API
   *
   * Helper to create [[Source]] from `Iterable`.
   * Example usage: `Source.from(Seq(1,2,3))`
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def from[O](iterable: java.lang.Iterable[O]): javadsl.Source[O] =
    SourceAdapter(scaladsl2.Source(akka.stream.javadsl.japi.Util.immutableIterable(iterable)))

  /**
   * Java API
   *
   * Define the sequence of elements to be produced by the given closure.
   * The stream ends normally when evaluation of the closure returns a `None`.
   * The stream ends exceptionally when an exception is thrown from the closure.
   */
  def from[O](f: japi.Creator[akka.japi.Option[O]]): javadsl.Source[O] =
    SourceAdapter(scaladsl2.Source(() ⇒ f.create().asScala))

  /**
   * Java API
   *
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def from[O](future: Future[O]): javadsl.Source[O] =
    SourceAdapter(scaladsl2.Source(future))

  /**
   * Java API
   *
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def from[O](initialDelay: FiniteDuration, interval: FiniteDuration, tick: Callable[O]): javadsl.Source[O] =
    SourceAdapter(scaladsl2.Source(initialDelay, interval, () ⇒ tick.call()))
}

/** INTERNAL API */
private[akka] object SourceAdapter {

  def apply[O](tap: scaladsl2.Tap[O]): javadsl.Source[O] =
    new SourceAdapter[O] { def delegate = scaladsl2.Pipe.empty[O].withTap(tap) }

  def apply[O](source: scaladsl2.Source[O]): javadsl.Source[O] =
    source match {
      case pipe: scaladsl2.SourcePipe[O] ⇒ apply(pipe)
      case _                             ⇒ apply(source.asInstanceOf[scaladsl2.Tap[O]])
    }

  def apply[O](pipe: scaladsl2.SourcePipe[O]): javadsl.Source[O] =
    new SourceAdapter[O] { def delegate = pipe }

}

/** INTERNAL API */
private[akka] abstract class SourceAdapter[+Out] extends Source[Out] {

  import scala.collection.JavaConverters._
  import akka.stream.scaladsl2.JavaConverters._

  protected def delegate: scaladsl2.Source[Out]

  /** Converts this Source to it's Scala DSL counterpart */
  def asScala: scaladsl2.Source[Out] = delegate

  // SOURCE //

  override def connect[T](flow: javadsl.Flow[Out, T]): javadsl.Source[T] =
    SourceAdapter(delegate.connect(flow.asScala))

  override def connect(sink: javadsl.Sink[Out]): javadsl.RunnableFlow =
    new RunnableFlowAdapter(delegate.connect(sink.asScala))

  override def runWith[D](drain: DrainWithKey[Out, D], materializer: FlowMaterializer): D =
    asScala.runWith(drain.asScala)(materializer).asInstanceOf[D]

  override def runWith(drain: SimpleDrain[Out], materializer: FlowMaterializer): Unit =
    delegate.connect(drain.asScala).run()(materializer)

  override def fold[U](zero: U, f: japi.Function2[U, Out, U], materializer: FlowMaterializer): Future[U] =
    runWith(FoldDrain.create(zero, f), materializer)

  override def concat[Out2 >: Out](second: javadsl.Source[Out2]): javadsl.Source[Out2] =
    delegate.concat(second.asScala).asJava

  override def foreach(f: japi.Procedure[Out], materializer: FlowMaterializer): Future[Unit] =
    runWith(ForeachDrain.create(f), materializer)

  // COMMON OPS //

  override def map[T](f: Function[Out, T]): javadsl.Source[T] =
    SourceAdapter(delegate.map(f.apply))

  override def mapConcat[T](f: Function[Out, java.util.List[T]]): javadsl.Source[T] =
    SourceAdapter(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  override def mapAsync[T](f: Function[Out, Future[T]]): javadsl.Source[T] =
    SourceAdapter(delegate.mapAsync(f.apply))

  override def mapAsyncUnordered[T](f: Function[Out, Future[T]]): javadsl.Source[T] =
    SourceAdapter(delegate.mapAsyncUnordered(f.apply))

  override def filter(p: Predicate[Out]): javadsl.Source[Out] =
    SourceAdapter(delegate.filter(p.test))

  override def collect[T](pf: PartialFunction[Out, T]): javadsl.Source[T] =
    SourceAdapter(delegate.collect(pf))

  override def grouped(n: Int): javadsl.Source[java.util.List[Out @uncheckedVariance]] =
    SourceAdapter(delegate.grouped(n).map(_.asJava))

  override def groupedWithin(n: Int, d: FiniteDuration): javadsl.Source[java.util.List[Out @uncheckedVariance]] =
    SourceAdapter(delegate.groupedWithin(n, d).map(_.asJava)) // FIXME optimize to one step

  override def drop(n: Int): javadsl.Source[Out] =
    SourceAdapter(delegate.drop(n))

  override def dropWithin(d: FiniteDuration): javadsl.Source[Out] =
    SourceAdapter(delegate.dropWithin(d))

  override def take(n: Int): javadsl.Source[Out] =
    SourceAdapter(delegate.take(n))

  override def takeWithin(d: FiniteDuration): javadsl.Source[Out] =
    SourceAdapter(delegate.takeWithin(d))

  override def conflate[S](seed: Function[Out, S], aggregate: Function2[S, Out, S]): javadsl.Source[S] =
    SourceAdapter(delegate.conflate(seed.apply, aggregate.apply))

  override def expand[S, U](seed: Function[Out, S], extrapolate: Function[S, akka.japi.Pair[U, S]]): javadsl.Source[U] =
    SourceAdapter(delegate.expand(seed.apply, (s: S) ⇒ {
      val p = extrapolate.apply(s)
      (p.first, p.second)
    }))

  override def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Source[Out] =
    SourceAdapter(delegate.buffer(size, overflowStrategy))

  override def transform[T](name: String, mkTransformer: japi.Creator[Transformer[Out, T]]): javadsl.Source[T] =
    SourceAdapter(delegate.transform(name, () ⇒ mkTransformer.create()))

  override def timerTransform[U](name: String, mkTransformer: Creator[TimerTransformer[Out, U]]): javadsl.Source[U] =
    SourceAdapter(delegate.timerTransform(name, () ⇒ mkTransformer.create()))

  override def prefixAndTail(n: Int): javadsl.Source[akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance]]] =
    SourceAdapter(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ akka.japi.Pair(taken.asJava, tail.asJava) })

  override def groupBy[K](f: japi.Function[Out, K]): javadsl.Source[akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance]]] =
    SourceAdapter(delegate.groupBy(f.apply).map { case (k, p) ⇒ akka.japi.Pair(k, p.asJava) }) // FIXME optimize to one step

  override def splitWhen(p: japi.Predicate[Out]): javadsl.Source[javadsl.Source[Out]] =
    SourceAdapter(delegate.splitWhen(p.test).map(_.asJava))

  override def flatten[U](strategy: FlattenStrategy[Out, U]): javadsl.Source[U] =
    SourceAdapter(delegate.flatten(strategy))

}
