/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.util.Try
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import akka.stream.FlowMaterializer
import akka.stream.RecoveryTransformer
import akka.stream.Transformer
import akka.stream.impl.DuctImpl

object Duct {

  private val empty = DuctImpl[Any, Any](Nil)

  /**
   * Create an empty [[Duct]]. The transformation steps are executed by a series
   * of [[org.reactivestreams.api.Processor]] instances that mediate the flow of
   * elements downstream and the propagation of back-pressure upstream.
   */
  def apply[In]: Duct[In, In] = empty.asInstanceOf[Duct[In, In]]

}

/**
 * A `Duct` provides the same kind of formulation of stream transformations as a [[Flow]].
 * The difference is that it is not attached to an input source.
 *
 * The pipeline must be materialized by calling the [[#produceTo]], [[#consume]] or [[#build]]
 * methods on it and then attach the `Consumer` representing the input side of the `Duct` to an
 * upstream `Producer`.
 *
 */
trait Duct[In, +Out] {
  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[U](f: Out ⇒ U): Duct[In, U]

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
   * Invoke the given procedure for each received element and produce a Unit value
   * upon reaching the normal end of the stream. Please note that also in this case
   * the `Duct` needs to be materialized (e.g. using [[#consume]] and attaching the
   * the `Consumer` representing the input side of the `Duct` to an upstream
   * `Producer`) to initiate its execution.
   */
  def foreach(c: Out ⇒ Unit): Duct[In, Unit]

  /**
   * Invoke the given function for every received element, giving it its previous
   * output (or the given “zero” value) and the element as input. The returned stream
   * will receive the return value of the final function evaluation when the input
   * stream ends.
   */
  def fold[U](zero: U)(f: (U, Out) ⇒ U): Duct[In, U]

  /**
   * Discard the given number of elements at the beginning of the stream.
   */
  def drop(n: Int): Duct[In, Out]

  /**
   * Terminate processing (and cancel the upstream producer) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   */
  def take(n: Int): Duct[In, Out]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   */
  def grouped(n: Int): Duct[In, immutable.Seq[Out]]

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
  def transform[U](transformer: Transformer[Out, U]): Duct[In, U]

  /**
   * This transformation stage works exactly like [[#transform]] with the
   * change that failure signaled from upstream will invoke
   * [[RecoveryTransformer#onError]], which can emit an additional sequence of
   * elements before the stream ends.
   *
   * After normal completion or error the [[RecoveryTransformer#cleanup]] function
   * is called.
   */
  def transformRecover[U](recoveryTransformer: RecoveryTransformer[Out, U]): Duct[In, U]

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
  def groupBy[K](f: Out ⇒ K): Duct[In, (K, Producer[Out @uncheckedVariance])]

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
  def splitWhen(p: Out ⇒ Boolean): Duct[In, Producer[Out @uncheckedVariance]]

  /**
   * Merge this stream with the one emitted by the given producer, taking
   * elements as they arrive from either side (picking randomly when both
   * have elements ready).
   */
  def merge[U >: Out](other: Producer[U]): Duct[In, U]

  /**
   * Zip this stream together with the one emitted by the given producer.
   * This transformation finishes when either input stream reaches its end,
   * cancelling the subscription to the other one.
   */
  def zip[U](other: Producer[U]): Duct[In, (Out, U)]

  /**
   * Concatenate the given other stream to this stream so that the first element
   * emitted by the given producer is emitted after the last element of this
   * stream.
   */
  def concat[U >: Out](next: Producer[U]): Duct[In, U]

  /**
   * Fan-out the stream to another consumer. Each element is produced to
   * the `other` consumer as well as to downstream consumers. It will
   * not shutdown until the subscriptions for `other` and at least
   * one downstream consumer have been established.
   */
  def tee(other: Consumer[_ >: Out]): Duct[In, Out]

  /**
   * Materialize this `Duct` by attaching it to the specified downstream `consumer`
   * and return a `Consumer` representing the input side of the `Duct`.
   * The returned `Consumer` can later be connected to an upstream `Producer`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def produceTo(materializer: FlowMaterializer, consumer: Consumer[Out] @uncheckedVariance): Consumer[In]

  /**
   * Attaches a consumer to this stream which will just discard all received
   * elements. The returned `Consumer` represents the input side of the `Duct` and can
   * later be connected to an upstream `Producer`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def consume(materializer: FlowMaterializer): Consumer[In]

  /**
   * When this flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]]. The returned `Consumer` represents the input side of
   * the `Duct` and can later be connected to an upstream `Producer`.
   *
   * *This operation materializes the flow and initiates its execution.*
   */
  def onComplete(materializer: FlowMaterializer)(callback: Try[Unit] ⇒ Unit): Consumer[In]

  /**
   * Materialize this `Duct` into a `Consumer` representing the input side of the `Duct`
   * and a `Producer`representing the output side of the the `Duct`.
   *
   * The returned `Producer` can later be connected to an downstream `Consumer`.
   * The returned `Consumer` can later be connected to an upstream `Producer`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def build(materializer: FlowMaterializer): (Consumer[In], Producer[Out] @uncheckedVariance)

}

