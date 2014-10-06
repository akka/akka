/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import org.reactivestreams.{ Subscriber, Publisher }

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import scala.language.higherKinds
import scala.language.implicitConversions

/**
 * A `Source` is a set of stream processing steps that has one open output and an attached input.
 * Can be used as a `Publisher`
 */
trait Source[+Out] extends FlowOps[Out] {
  override type Repr[+O] <: Source[O]

  /**
   * Transform this source by appending the given processing stages.
   */
  def connect[T](flow: Flow[Out, T]): Source[T]

  /**
   * Connect this source to a sink, concatenating the processing steps of both.
   */
  def connect(sink: Sink[Out]): RunnableFlow

  /**
   * Connect this `Source` to a `Drain` and run it. The returned value is the materialized value
   * of the `Drain`, e.g. the `Publisher` of a [[PublisherDrain]].
   */
  def runWith(drain: DrainWithKey[Out])(implicit materializer: FlowMaterializer): drain.MaterializedType

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U](zero: U)(f: (U, Out) ⇒ U)(implicit materializer: FlowMaterializer): Future[U] =
    runWith(FoldDrain(zero)(f))

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream.
   */
  def foreach(f: Out ⇒ Unit)(implicit materializer: FlowMaterializer): Future[Unit] =
    runWith(ForeachDrain(f))

}

object Source {
  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](publisher: Publisher[T]): Source[T] = PublisherTap(publisher)

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source(Seq(1,2,3).iterator)`
   *
   * Start a new `Source` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def apply[T](iterator: Iterator[T]): Source[T] = IteratorTap(iterator)

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage: `Source(Seq(1,2,3))`
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Source[T] = IterableTap(iterable)

  /**
   * Define the sequence of elements to be produced by the given closure.
   * The stream ends normally when evaluation of the closure returns a `None`.
   * The stream ends exceptionally when an exception is thrown from the closure.
   */
  def apply[T](f: () ⇒ Option[T]): Source[T] = ThunkTap(f)

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): Source[T] = FutureTap(future)

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ T): Source[T] =
    TickTap(initialDelay, interval, tick)

}
