/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NoStackTrace

import org.reactivestreams.api.Producer

import akka.stream.FlowMaterializer
import akka.stream.impl.Ast.{ ExistingProducer, IterableProducerNode, IteratorProducerNode, ThunkProducerNode }
import akka.stream.impl.FlowImpl

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

}

/**
 * The Flow DSL allows the formulation of stream transformations based on some
 * input. The starting point can be a collection, an iterator, a block of code
 * which is evaluated repeatedly or a [[org.reactivestreams.api.Producer]].
 *
 * See <a href="https://github.com/reactive-streams/reactive-streams/">Reactive Streams</a> for details.
 *
 * Each DSL element produces a new Flow that can be further transformed, building
 * up a description of the complete transformation pipeline. In order to execute
 * this pipeline the Flow must be materialized by calling the [[#toFuture]], [[#consume]]
 * or [[#toProducer]] methods on it.
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
   * Invoke the given procedure for each received element and produce a Unit value
   * upon reaching the normal end of the stream. Please note that also in this case
   * the flow needs to be materialized (e.g. using [[#consume]]) to initiate its
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
   * Terminate processing (and cancel the upstream producer) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   */
  def take(n: Int): Flow[T]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   */
  def grouped(n: Int): Flow[immutable.Seq[T]]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[U](f: T ⇒ immutable.Seq[U]): Flow[U]

  /**
   * Generic transformation of a stream: for each element the [[Transformer#onNext]]
   * function is invoked and expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * consumers, the [[Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream consumers,
   * the [[Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * After normal completion or error the [[Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[Transformer]] instance with
   * ordinary instance variables. The [[Transformer]] is executed by an actor and
   * therefore you don not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   */
  def transform[U](transformer: Transformer[T, U]): Flow[U]

  /**
   * This transformation stage works exactly like [[#transform]] with the
   * change that normal input elements are wrapped in [[scala.util.Success]]
   * and failure signaled from upstream (i.e. <code>onError()</code> calls)
   * is also handled as normal input element wrapped in [[scala.util.Failure]].
   * In the latter case the stream ends after processing the failure.
   *
   * After normal completion or error the [[RecoveryTransformer#cleanup]] function
   * is called.
   */
  def transformRecover[U](recoveryTransformer: RecoveryTransformer[T, U]): Flow[U]

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

}

/**
 * General interface for stream transformation.
 * @see [[Flow#transform]]
 */
trait Transformer[-T, +U] {
  def onNext(element: T): immutable.Seq[U]
  def isComplete: Boolean = false
  def onComplete(): immutable.Seq[U] = Nil
  def cleanup(): Unit = ()
}

/**
 * General interface for stream transformation.
 * @see [[Flow#transformRecover]]
 */
trait RecoveryTransformer[-T, +U] extends Transformer[Try[T], U]

