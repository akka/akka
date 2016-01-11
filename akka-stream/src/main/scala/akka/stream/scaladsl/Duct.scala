/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.util.Try
import org.reactivestreams.{ Publisher, Subscriber }
import akka.stream._
import akka.stream.impl.DuctImpl
import akka.stream.impl.Ast
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future

object Duct {

  private val empty = DuctImpl[Any, Any](Nil)

  /**
   * Create an empty [[Duct]]. The transformation steps are executed by a series
   * of [[org.reactivestreams.Processor]] instances that mediate the flow of
   * elements downstream and the propagation of back-pressure upstream.
   */
  def apply[In]: Duct[In, In] = empty.asInstanceOf[Duct[In, In]]

}

/**
 * A `Duct` provides the same kind of formulation of stream transformations as a [[Flow]].
 * The difference is that it is not attached to an input source.
 *
 * The pipeline must be materialized by calling the [[#produceTo]], [[#consume]] or [[#build]]
 * methods on it and then attach the `Subscriber` representing the input side of the `Duct` to an
 * upstream `Publisher`.
 *
 * Use [[ImplicitFlowMaterializer]] to define an implicit [[akka.stream.FlowMaterializer]]
 * inside an [[akka.actor.Actor]].
 */
trait Duct[In, +Out] {
  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[U](f: Out ⇒ U): Duct[In, U]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   */
  def mapFuture[U](f: Out ⇒ Future[U]): Duct[In, U]

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: Out ⇒ Boolean): Duct[In, Out]

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[U](pf: PartialFunction[Out, U]): Duct[In, U]

  /**
   * Invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input. The returned stream
   * will receive the return value of the final function evaluation when the input
   * stream ends.
   */
  def fold[U](zero: U)(f: (U, Out) ⇒ U): Duct[In, U]

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): Duct[In, Out]

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Duct[In, Out]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   */
  def take(n: Int): Duct[In, Out]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Duct[In, Out]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): Duct[In, immutable.Seq[Out]]

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
  def groupedWithin(n: Int, d: FiniteDuration): Duct[In, immutable.Seq[Out]]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[U](f: Out ⇒ immutable.Seq[U]): Duct[In, U]

  /**
   * Generic transformation of a stream: for each element the [[Transformer#onNext]]
   * function is invoked and expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * subscribers, the [[Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream subscribers,
   * the [[Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * After normal completion or error the [[Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[Transformer]] instance with
   * ordinary instance variables. The [[Transformer]] is executed by an actor and
   * therefore you don not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#timerTransform]] if you need support for scheduled events in the transformer.
   */
  def transform[U](name: String, transformer: () ⇒ Transformer[Out, U]): Duct[In, U]

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
  def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[Out, U]): Duct[In, U]

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail[U >: Out](n: Int): Duct[In, (immutable.Seq[Out], Publisher[U])]

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
  def groupBy[K, U >: Out](f: Out ⇒ K): Duct[In, (K, Publisher[U])]

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
  def splitWhen[U >: Out](p: Out ⇒ Boolean): Duct[In, Publisher[U]]

  /**
   * Merge this stream with the one emitted by the given publisher, taking
   * elements as they arrive from either side (picking randomly when both
   * have elements ready).
   */
  def merge[U >: Out](other: Publisher[_ <: U]): Duct[In, U]

  /**
   * Zip this stream together with the one emitted by the given publisher.
   * This transformation finishes when either input stream reaches its end,
   * cancelling the subscription to the other one.
   */
  def zip[U](other: Publisher[U]): Duct[In, (Out, U)]

  /**
   * Concatenate the given other stream to this stream so that the first element
   * emitted by the given publisher is emitted after the last element of this
   * stream.
   */
  def concat[U >: Out](next: Publisher[U]): Duct[In, U]

  /**
   * Fan-out the stream to another subscriber. Each element is produced to
   * the `other` subscriber as well as to downstream subscribers. It will
   * not shutdown until the subscriptions for `other` and at least
   * one downstream subscriber have been established.
   */
  def broadcast(other: Subscriber[_ >: Out]): Duct[In, Out]

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Publisher]].
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): Duct[In, U]

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
  def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Duct[In, S]

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
  def expand[S, U](seed: Out ⇒ S, extrapolate: S ⇒ (U, S)): Duct[In, U]

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[OverflowStrategy]] it might drop elements or backpressure the upstream if there is no
   * space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Duct[In, Out]

  /**
   * Append the operations of a [[Duct]] to this `Duct`.
   */
  def append[U](duct: Duct[_ >: Out, U]): Duct[In, U]

  /**
   * INTERNAL API
   */
  private[akka] def appendJava[U](duct: akka.stream.javadsl.Duct[_ >: Out, U]): Duct[In, U]

  /**
   * Materialize this `Duct` by attaching it to the specified downstream `subscriber`
   * and return a `Subscriber` representing the input side of the `Duct`.
   * The returned `Subscriber` can later be connected to an upstream `Publisher`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given `FlowMaterializer` decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def produceTo[U >: Out](subscriber: Subscriber[U])(implicit materializer: FlowMaterializer): Subscriber[In]

  /**
   * Attaches a subscriber to this stream which will just discard all received
   * elements. The returned `Subscriber` represents the input side of the `Duct` and can
   * later be connected to an upstream `Publisher`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given `FlowMaterializer` decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def consume()(implicit materializer: FlowMaterializer): Subscriber[In]

  /**
   * When this flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]]. The returned `Subscriber` represents the input side of
   * the `Duct` and can later be connected to an upstream `Publisher`.
   *
   * *This operation materializes the flow and initiates its execution.*
   */
  def onComplete(callback: Try[Unit] ⇒ Unit)(implicit materializer: FlowMaterializer): Subscriber[In]

  /**
   * Materialize this `Duct` into a `Subscriber` representing the input side of the `Duct`
   * and a `Publisher`representing the output side of the the `Duct`.
   *
   * The returned `Publisher` can later be connected to an downstream `Subscriber`.
   * The returned `Subscriber` can later be connected to an upstream `Publisher`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given `FlowMaterializer` decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def build[U >: Out]()(implicit materializer: FlowMaterializer): (Subscriber[In], Publisher[U])

  /**
   * Invoke the given procedure for each received element.
   * Returns a tuple of a `Subscriber` and a `Future`.
   *
   * The returned `Subscriber` represents the input side of the `Duct` and can
   * later be connected to an upstream `Publisher`.
   *
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when
   * reaching the normal end of the stream, or completed
   * with `Failure` if there is an error is signaled in the stream.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given `FlowMaterializer` decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def foreach(c: Out ⇒ Unit)(implicit materializer: FlowMaterializer): (Subscriber[In], Future[Unit])

  /**
   * INTERNAL API
   * Used by `Flow.append(duct)`.
   */
  private[akka] def ops: immutable.Seq[Ast.AstNode]

}

