/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import org.reactivestreams.{ Publisher, Subscriber }
import akka.stream._
import akka.stream.impl.Ast.{ ExistingPublisher, IterablePublisherNode, IteratorPublisherNode, ThunkPublisherNode }
import akka.stream.impl.Ast.FuturePublisherNode
import akka.stream.impl.FlowImpl
import akka.stream.impl.Ast.TickPublisherNode
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.FiniteDuration

/**
 * Scala API
 */
object Flow {
  /**
   * Construct a transformation of the given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](publisher: Publisher[T]): Flow[T] = FlowImpl(ExistingPublisher(publisher), Nil)

  /**
   * Start a new flow from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the <code>next()</code> method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def apply[T](iterator: Iterator[T]): Flow[T] = FlowImpl(IteratorPublisherNode(iterator), Nil)

  /**
   * Start a new flow from the given Iterable. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Flow[T] = FlowImpl(IterablePublisherNode(iterable), Nil)

  /**
   * Define the sequence of elements to be produced by the given closure.
   * The stream ends normally when evaluation of the closure results in
   * a [[akka.stream.Stop]] exception being thrown; it ends exceptionally
   * when any other exception is thrown.
   */
  def apply[T](f: () ⇒ T): Flow[T] = FlowImpl(ThunkPublisherNode(f), Nil)

  /**
   * Start a new flow from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): Flow[T] = FlowImpl(FuturePublisherNode(future), Nil)

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ T): Flow[T] = FlowImpl(TickPublisherNode(initialDelay, interval, tick), Nil)
}

/**
 * Scala API: The Flow DSL allows the formulation of stream transformations based on some
 * input. The starting point can be a collection, an iterator, a block of code
 * which is evaluated repeatedly or a [[org.reactivestreams.Publisher]].
 *
 * See <a href="https://github.com/reactive-streams/reactive-streams/">Reactive Streams</a> for details.
 *
 * Each DSL element produces a new Flow that can be further transformed, building
 * up a description of the complete transformation pipeline. In order to execute
 * this pipeline the Flow must be materialized by calling the [[#toFuture]], [[#consume]],
 * [[#onComplete]], or [[#toPublisher]] methods on it.
 *
 * It should be noted that the streams modeled by this library are “hot”,
 * meaning that they asynchronously flow through a series of processors without
 * detailed control by the user. In particular it is not predictable how many
 * elements a given transformation step might buffer before handing elements
 * downstream, which means that transformation functions may be invoked more
 * often than for corresponding transformations on strict collections like
 * [[List]]. *An important consequence* is that elements that were produced
 * into a stream may be discarded by later processors, e.g. when using the
 * [[#take]] combinator.
 *
 * By default every operation is executed within its own [[akka.actor.Actor]]
 * to enable full pipelining of the chained set of computations. This behavior
 * is determined by the [[akka.stream.FlowMaterializer]] which is required
 * by those methods that materialize the Flow into a series of
 * [[org.reactivestreams.Processor]] instances. The returned reactive stream
 * is fully started and active.
 *
 * Use [[ImplicitFlowMaterializer]] to define an implicit [[akka.stream.FlowMaterializer]]
 * inside an [[akka.actor.Actor]].
 */
trait Flow[+T] {

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[U](f: T ⇒ U): Flow[U]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   */
  def mapFuture[U](f: T ⇒ Future[U]): Flow[U]

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: T ⇒ Boolean): Flow[T]

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[U](pf: PartialFunction[T, U]): Flow[U]

  /**
   * Invoke the given function for every received element, giving it its previous
   * output (or the given “zero” value) and the element as input. The returned stream
   * will receive the return value of the final function evaluation when the input
   * stream ends.
   */
  def fold[U](zero: U)(f: (U, T) ⇒ U): Flow[U]

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): Flow[T]

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Flow[T]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   */
  def take(n: Int): Flow[T]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Flow[T]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): Flow[immutable.Seq[T]]

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  def groupedWithin(n: Int, d: FiniteDuration): Flow[immutable.Seq[T]]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[U](f: T ⇒ immutable.Seq[U]): Flow[U]

  /**
   * Generic transformation of a stream: for each element the [[akka.stream.Transformer#onNext]]
   * function is invoked, expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * subscribers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream subscribers,
   * the [[akka.stream.Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * [[akka.stream.Transformer#onError]] is called when failure is signaled from upstream.
   *
   * After normal completion or error the [[akka.stream.Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you do not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#timerTransform]] if you need support for scheduled events in the transformer.
   */
  def transform[U](name: String, mkTransformer: () ⇒ Transformer[T, U]): Flow[U]

  /**
   * Transformation of a stream, with additional support for scheduled events.
   *
   * For each element the [[akka.stream.Transformer#onNext]]
   * function is invoked, expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * subscribers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream subscribers,
   * the [[akka.stream.Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * [[akka.stream.Transformer#onError]] is called when failure is signaled from upstream.
   *
   * After normal completion or error the [[akka.stream.Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you do not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#transform]] if you just need to transform elements time plays no role in the transformation.
   */
  def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[T, U]): Flow[U]

  /**
   * Takes up to n elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail[U >: T](n: Int): Flow[(immutable.Seq[T], Publisher[U])]

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * it is emitted to the downstream subscriber together with a fresh
   * publisher that will eventually produce all the elements of the substream
   * for that key. Not consuming the elements from the created streams will
   * stop this processor from processing more elements, therefore you must take
   * care to unblock (or cancel) all of the produced streams even if you want
   * to consume only one of them.
   */
  def groupBy[K, U >: T](f: T ⇒ K): Flow[(K, Publisher[U])]

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it. This means
   * that for the following series of predicate values, three substreams will
   * be produced with lengths 1, 2, and 3:
   *
   * {{{
   * false,             // element goes into first substream
   * true, false,       // elements go into second substream
   * true, false, false // elements go into third substream
   * }}}
   */
  def splitWhen[U >: T](p: T ⇒ Boolean): Flow[Publisher[U]]

  /**
   * Merge this stream with the one emitted by the given publisher, taking
   * elements as they arrive from either side (picking randomly when both
   * have elements ready).
   */
  def merge[U >: T](other: Publisher[_ <: U]): Flow[U]

  /**
   * Zip this stream together with the one emitted by the given publisher.
   * This transformation finishes when either input stream reaches its end,
   * cancelling the subscription to the other one.
   */
  def zip[U](other: Publisher[U]): Flow[(T, U)]

  /**
   * Concatenate the given other stream to this stream so that the first element
   * emitted by the given publisher is emitted after the last element of this
   * stream.
   */
  def concat[U >: T](next: Publisher[U]): Flow[U]

  /**
   * Fan-out the stream to another subscriber. Each element is produced to
   * the `other` subscriber as well as to downstream subscribers. It will
   * not shutdown until the subscriptions for `other` and at least
   * one downstream subscriber have been established.
   */
  def broadcast(other: Subscriber[_ >: T]): Flow[T]

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Publisher]].
   */
  def flatten[U](strategy: FlattenStrategy[T, U]): Flow[U]

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate[S](seed: T ⇒ S, aggregate: (S, T) ⇒ S): Flow[S]

  /**
   * Allows a faster downstream to progress independently of a slower publisher by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: T ⇒ S, extrapolate: S ⇒ (U, S)): Flow[U]

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[OverflowStrategy]] it might drop elements or backpressure the upstream if there is no
   * space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Flow[T]

  /**
   * Append the operations of a [[Duct]] to this flow.
   */
  def append[U](duct: Duct[_ >: T, U]): Flow[U]

  /**
   * INTERNAL API
   */
  private[akka] def appendJava[U](duct: akka.stream.javadsl.Duct[_ >: T, U]): Flow[U]

  /**
   * Returns a [[scala.concurrent.Future]] that will be fulfilled with the first
   * thing that is signaled to this stream, which can be either an element (after
   * which the upstream subscription is canceled), an error condition (putting
   * the Future into the corresponding failed state) or the end-of-stream
   * (failing the Future with a NoSuchElementException). *This operation
   * materializes the flow and initiates its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def toFuture()(implicit materializer: FlowMaterializer): Future[T]

  /**
   * Attaches a subscriber to this stream which will just discard all received
   * elements. *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def consume()(implicit materializer: FlowMaterializer): Unit

  /**
   * When this flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   *
   * *This operation materializes the flow and initiates its execution.*
   */
  def onComplete(callback: Try[Unit] ⇒ Unit)(implicit materializer: FlowMaterializer): Unit

  /**
   * Materialize this flow and return the downstream-most
   * [[org.reactivestreams.Publisher]] interface. The stream will not have
   * any subscribers attached at this point, which means that after prefetching
   * elements to fill the internal buffers it will assert back-pressure until
   * a subscriber connects and creates demand for elements to be emitted.
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def toPublisher[U >: T]()(implicit materializer: FlowMaterializer): Publisher[U]

  /**
   * Attaches a subscriber to this stream.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def produceTo(subscriber: Subscriber[_ >: T])(implicit materializer: FlowMaterializer): Unit

  /**
   * Invoke the given procedure for each received element. Returns a [[scala.concurrent.Future]]
   * that will be completed with `Success` when reaching the normal end of the stream, or completed
   * with `Failure` if there is an error is signaled in the stream.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def foreach(c: T ⇒ Unit)(implicit materializer: FlowMaterializer): Future[Unit]

}

