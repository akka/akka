/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import akka.stream.{ FlattenStrategy, OverflowStrategy, FlowMaterializer, Transformer }
import akka.stream.impl.Ast.{ ExistingProducer, IterableProducerNode, IteratorProducerNode, ThunkProducerNode }
import akka.stream.impl.Ast.FutureProducerNode
import akka.stream.impl.FlowImpl
import akka.stream.impl.Ast.TickProducerNode
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.FiniteDuration

/**
 * Scala API
 */
object Flow {
  /**
   * Construct a transformation of the given producer. The transformation steps
   * are executed by a series of [[org.reactivestreams.api.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](producer: Producer[T]): Flow[T] = FlowImpl(ExistingProducer(producer), Nil)

  /**
   * Start a new flow from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the <code>next()</code> method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def apply[T](iterator: Iterator[T]): Flow[T] = FlowImpl(IteratorProducerNode(iterator), Nil)

  /**
   * Start a new flow from the given Iterable. This is like starting from an
   * Iterator, but every Consumer directly attached to the Producer of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Flow[T] = FlowImpl(IterableProducerNode(iterable), Nil)

  /**
   * Define the sequence of elements to be produced by the given closure.
   * The stream ends normally when evaluation of the closure results in
   * a [[akka.stream.Stop]] exception being thrown; it ends exceptionally
   * when any other exception is thrown.
   */
  def apply[T](f: () ⇒ T): Flow[T] = FlowImpl(ThunkProducerNode(f), Nil)

  /**
   * Start a new flow from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): Flow[T] = FlowImpl(FutureProducerNode(future), Nil)

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](interval: FiniteDuration, tick: () ⇒ T): Flow[T] = FlowImpl(TickProducerNode(interval, tick), Nil)

}

/**
 * Scala API: The Flow DSL allows the formulation of stream transformations based on some
 * input. The starting point can be a collection, an iterator, a block of code
 * which is evaluated repeatedly or a [[org.reactivestreams.api.Producer]].
 *
 * See <a href="https://github.com/reactive-streams/reactive-streams/">Reactive Streams</a> for details.
 *
 * Each DSL element produces a new Flow that can be further transformed, building
 * up a description of the complete transformation pipeline. In order to execute
 * this pipeline the Flow must be materialized by calling the [[#toFuture]], [[#consume]],
 * [[#onComplete]], or [[#toProducer]] methods on it.
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
 * [[org.reactivestreams.api.Processor]] instances. The returned reactive stream
 * is fully started and active.
 */
trait Flow[+T] {

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[U](f: T ⇒ U): Flow[U]

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
   * Invoke the given procedure for each received element and produce a Unit value
   * upon reaching the normal end of the stream. Please note that also in this case
   * the `Flow` needs to be materialized (e.g. using [[#consume]]) to initiate its
   * execution.
   */
  def foreach(c: T ⇒ Unit): Flow[Unit]

  /**
   * Invoke the given function for every received element, giving it its previous
   * output (or the given “zero” value) and the element as input. The returned stream
   * will receive the return value of the final function evaluation when the input
   * stream ends.
   */
  def fold[U](zero: U)(f: (U, T) ⇒ U): Flow[U]

  /**
   * Discard the given number of elements at the beginning of the stream.
   */
  def drop(n: Int): Flow[T]

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Flow[T]

  /**
   * Terminate processing (and cancel the upstream producer) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   */
  def take(n: Int): Flow[T]

  /**
   * Terminate processing (and cancel the upstream producer) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Flow[T]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   */
  def grouped(n: Int): Flow[immutable.Seq[T]]

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
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
   * consumers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream consumers,
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
   * Note that you can use [[akka.stream.TimerTransformer]] if you need support
   * for scheduled events in the transformer.
   */
  def transform[U](transformer: Transformer[T, U]): Flow[U]

  /**
   * Takes up to n elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail(n: Int): Flow[(immutable.Seq[T], Producer[T @uncheckedVariance])]

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * it is emitted to the downstream consumer together with a fresh
   * producer that will eventually produce all the elements of the substream
   * for that key. Not consuming the elements from the created streams will
   * stop this processor from processing more elements, therefore you must take
   * care to unblock (or cancel) all of the produced streams even if you want
   * to consume only one of them.
   */
  def groupBy[K](f: T ⇒ K): Flow[(K, Producer[T @uncheckedVariance])]

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
  def splitWhen(p: T ⇒ Boolean): Flow[Producer[T @uncheckedVariance]]

  /**
   * Merge this stream with the one emitted by the given producer, taking
   * elements as they arrive from either side (picking randomly when both
   * have elements ready).
   */
  def merge[U >: T](other: Producer[U]): Flow[U]

  /**
   * Zip this stream together with the one emitted by the given producer.
   * This transformation finishes when either input stream reaches its end,
   * cancelling the subscription to the other one.
   */
  def zip[U](other: Producer[U]): Flow[(T, U)]

  /**
   * Concatenate the given other stream to this stream so that the first element
   * emitted by the given producer is emitted after the last element of this
   * stream.
   */
  def concat[U >: T](next: Producer[U]): Flow[U]

  /**
   * Fan-out the stream to another consumer. Each element is produced to
   * the `other` consumer as well as to downstream consumers. It will
   * not shutdown until the subscriptions for `other` and at least
   * one downstream consumer have been established.
   */
  def tee(other: Consumer[_ >: T]): Flow[T]

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Producer]].
   */
  def flatten[U](strategy: FlattenStrategy[T, U]): Flow[U]

  /**
   * Allows a faster upstream to progress independently of a slower consumer by conflating elements into a summary
   * until the consumer is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream producer is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate[S](seed: T ⇒ S, aggregate: (S, T) ⇒ S): Flow[S]

  /**
   * Allows a faster downstream to progress independently of a slower producer by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the consumer until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * consumer.
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
  def toFuture(materializer: FlowMaterializer): Future[T]

  /**
   * Attaches a consumer to this stream which will just discard all received
   * elements. *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def consume(materializer: FlowMaterializer): Unit

  /**
   * When this flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   *
   * *This operation materializes the flow and initiates its execution.*
   */
  def onComplete(materializer: FlowMaterializer)(callback: Try[Unit] ⇒ Unit): Unit

  /**
   * Materialize this flow and return the downstream-most
   * [[org.reactivestreams.api.Producer]] interface. The stream will not have
   * any consumers attached at this point, which means that after prefetching
   * elements to fill the internal buffers it will assert back-pressure until
   * a consumer connects and creates demand for elements to be emitted.
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def toProducer(materializer: FlowMaterializer): Producer[T @uncheckedVariance]

  /**
   * Attaches a consumer to this stream.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def produceTo(materializer: FlowMaterializer, consumer: Consumer[_ >: T]): Unit

}

