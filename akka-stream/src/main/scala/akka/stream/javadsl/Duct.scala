/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.util.Failure
import scala.util.Success
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import akka.japi.Function
import akka.japi.Function2
import akka.japi.Pair
import akka.japi.Predicate
import akka.japi.Procedure
import akka.japi.Util.immutableSeq
import akka.stream.{ FlattenStrategy, OverflowStrategy, FlowMaterializer, Transformer }
import akka.stream.scaladsl.{ Duct ⇒ SDuct }
import akka.stream.impl.Ast
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future

/**
 * Java API
 */
object Duct {

  /**
   * Create an empty [[Duct]]. The transformation steps are executed by a series
   * of [[org.reactivestreams.api.Processor]] instances that mediate the flow of
   * elements downstream and the propagation of back-pressure upstream.
   */
  def create[In](inputType: Class[In]): Duct[In, In] = new DuctAdapter(SDuct.apply[In])

}

/**
 * Java API: A `Duct` provides the same kind of formulation of stream transformations as a [[Flow]].
 * The difference is that it is not attached to an input source.
 *
 * The pipeline must be materialized by calling the [[#produceTo]], [[#consume]] or [[#build]]
 * methods on it and then attach the `Consumer` representing the input side of the `Duct` to an
 * upstream `Producer`.
 *
 */
abstract class Duct[In, Out] {

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[U](f: Function[Out, U]): Duct[In, U]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   */
  def mapFuture[U](f: Function[Out, Future[U]]): Duct[In, U]

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: Predicate[Out]): Duct[In, Out]

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Use [[akka.japi.pf.PFBuilder]] to construct the `PartialFunction`.
   */
  def collect[U](pf: PartialFunction[Out, U]): Duct[In, U]

  /**
   * Invoke the given procedure for each received element and produce a Unit value
   * upon reaching the normal end of the stream. Please note that also in this case
   * the `Duct` needs to be materialized (e.g. using [[#consume]] and attaching the
   * the `Consumer` representing the input side of the `Duct` to an upstream
   * `Producer`) to initiate its execution.
   */
  def foreach(c: Procedure[Out]): Duct[In, Void]

  /**
   * Invoke the given function for every received element, giving it its previous
   * output (or the given “zero” value) and the element as input. The returned stream
   * will receive the return value of the final function evaluation when the input
   * stream ends.
   */
  def fold[U](zero: U, f: Function2[U, Out, U]): Duct[In, U]

  /**
   * Discard the given number of elements at the beginning of the stream.
   */
  def drop(n: Int): Duct[In, Out]

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Duct[In, Out]

  /**
   * Terminate processing (and cancel the upstream producer) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   */
  def take(n: Int): Duct[In, Out]

  /**
   * Terminate processing (and cancel the upstream producer) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Duct[In, Out]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   */
  def grouped(n: Int): Duct[In, java.util.List[Out]]

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   */
  def groupedWithin(n: Int, d: FiniteDuration): Duct[In, java.util.List[Out]]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[U](f: Function[Out, java.util.List[U]]): Duct[In, U]

  /**
   * Generic transformation of a stream: for each element the [[akka.stream.Transformer#onNext]]
   * function is invoked and expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * consumers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream consumers,
   * the [[akka.stream.Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * After normal completion or error the [[akka.stream.Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you don not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[akka.stream.TimerTransformer]] if you need support
   * for scheduled events in the transformer.
   */
  def transform[U](transformer: Transformer[Out, U]): Duct[In, U]

  /**
   * Takes up to n elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail(n: Int): Duct[In, Pair[java.util.List[Out], Producer[Out]]]

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
  def groupBy[K](f: Function[Out, K]): Duct[In, Pair[K, Producer[Out]]]

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
  def splitWhen(p: Predicate[Out]): Duct[In, Producer[Out]]

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
  def zip[U](other: Producer[U]): Duct[In, Pair[Out, U]]

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
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Producer]].
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): Duct[In, U]

  /**
   * Append the operations of a [[Duct]] to this `Duct`.
   */
  def append[U](duct: Duct[_ >: Out, U]): Duct[In, U]

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
  def conflate[S](seed: Function[Out, S], aggregate: Function2[S, Out, S]): Duct[In, S]

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
  def expand[S, U](seed: Function[Out, S], extrapolate: Function[S, Pair[U, S]]): Duct[In, U]

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
   * Materialize this `Duct` by attaching it to the specified downstream `consumer`
   * and return a `Consumer` representing the input side of the `Duct`.
   * The returned `Consumer` can later be connected to an upstream `Producer`.
   *
   * *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def produceTo(materializer: FlowMaterializer, consumer: Consumer[Out]): Consumer[In]

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
  def onComplete(materializer: FlowMaterializer)(callback: OnCompleteCallback): Consumer[In]

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
  def build(materializer: FlowMaterializer): Pair[Consumer[In], Producer[Out]]

  /**
   * INTERNAL API
   * Used by `Flow.append(duct)`.
   */
  private[akka] def ops: immutable.Seq[Ast.AstNode]

}

/**
 * INTERNAL API
 */
private[akka] class DuctAdapter[In, T](delegate: SDuct[In, T]) extends Duct[In, T] {
  override def map[U](f: Function[T, U]): Duct[In, U] = new DuctAdapter(delegate.map(f.apply))

  override def mapFuture[U](f: Function[T, Future[U]]): Duct[In, U] = new DuctAdapter(delegate.mapFuture(f.apply))

  override def filter(p: Predicate[T]): Duct[In, T] = new DuctAdapter(delegate.filter(p.test))

  override def collect[U](pf: PartialFunction[T, U]): Duct[In, U] = new DuctAdapter(delegate.collect(pf))

  override def foreach(c: Procedure[T]): Duct[In, Void] =
    new DuctAdapter(delegate.foreach(c.apply).map(_ ⇒ null)) // FIXME optimize to one step 

  override def fold[U](zero: U, f: Function2[U, T, U]): Duct[In, U] =
    new DuctAdapter(delegate.fold(zero) { case (a, b) ⇒ f.apply(a, b) })

  override def drop(n: Int): Duct[In, T] = new DuctAdapter(delegate.drop(n))

  override def dropWithin(d: FiniteDuration): Duct[In, T] = new DuctAdapter(delegate.dropWithin(d))

  override def take(n: Int): Duct[In, T] = new DuctAdapter(delegate.take(n))

  override def takeWithin(d: FiniteDuration): Duct[In, T] = new DuctAdapter(delegate.takeWithin(d))

  override def grouped(n: Int): Duct[In, java.util.List[T]] =
    new DuctAdapter(delegate.grouped(n).map(_.asJava)) // FIXME optimize to one step

  def groupedWithin(n: Int, d: FiniteDuration): Duct[In, java.util.List[T]] =
    new DuctAdapter(delegate.groupedWithin(n, d).map(_.asJava)) // FIXME optimize to one step

  override def mapConcat[U](f: Function[T, java.util.List[U]]): Duct[In, U] =
    new DuctAdapter(delegate.mapConcat(elem ⇒ immutableSeq(f.apply(elem))))

  override def transform[U](transformer: Transformer[T, U]): Duct[In, U] =
    new DuctAdapter(delegate.transform(transformer))

  /**
   * Takes up to n elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements.
   */
  override def prefixAndTail(n: Int): Duct[In, Pair[java.util.List[T], Producer[T]]] =
    new DuctAdapter(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ Pair(taken.asJava, tail) })

  override def groupBy[K](f: Function[T, K]): Duct[In, Pair[K, Producer[T]]] =
    new DuctAdapter(delegate.groupBy(f.apply).map { case (k, p) ⇒ Pair(k, p) }) // FIXME optimize to one step

  override def splitWhen(p: Predicate[T]): Duct[In, Producer[T]] =
    new DuctAdapter(delegate.splitWhen(p.test))

  override def merge[U >: T](other: Producer[U]): Duct[In, U] =
    new DuctAdapter(delegate.merge(other))

  override def zip[U](other: Producer[U]): Duct[In, Pair[T, U]] =
    new DuctAdapter(delegate.zip(other).map { case (k, p) ⇒ Pair(k, p) }) // FIXME optimize to one step

  override def concat[U >: T](next: Producer[U]): Duct[In, U] =
    new DuctAdapter(delegate.concat(next))

  override def tee(other: Consumer[_ >: T]): Duct[In, T] =
    new DuctAdapter(delegate.tee(other))

  override def buffer(size: Int, overflowStrategy: OverflowStrategy): Duct[In, T] =
    new DuctAdapter(delegate.buffer(size, overflowStrategy))

  override def expand[S, U](seed: Function[T, S], extrapolate: Function[S, Pair[U, S]]): Duct[In, U] =
    new DuctAdapter(delegate.expand(seed.apply, (s: S) ⇒ {
      val p = extrapolate.apply(s)
      (p.first, p.second)
    }))

  override def conflate[S](seed: Function[T, S], aggregate: Function2[S, T, S]): Duct[In, S] =
    new DuctAdapter(delegate.conflate(seed.apply, aggregate.apply))

  override def flatten[U](strategy: FlattenStrategy[T, U]): Duct[In, U] =
    new DuctAdapter(delegate.flatten(strategy))

  override def append[U](duct: Duct[_ >: T, U]): Duct[In, U] =
    new DuctAdapter(delegate.appendJava(duct))

  override def produceTo(materializer: FlowMaterializer, consumer: Consumer[T]): Consumer[In] =
    delegate.produceTo(materializer, consumer)

  override def consume(materializer: FlowMaterializer): Consumer[In] =
    delegate.consume(materializer)

  override def onComplete(materializer: FlowMaterializer)(callback: OnCompleteCallback): Consumer[In] =
    delegate.onComplete(materializer) {
      case Success(_) ⇒ callback.onComplete(null)
      case Failure(e) ⇒ callback.onComplete(e)
    }

  override def build(materializer: FlowMaterializer): Pair[Consumer[In], Producer[T]] = {
    val (in, out) = delegate.build(materializer)
    Pair(in, out)
  }

  override private[akka] def ops: immutable.Seq[Ast.AstNode] = delegate.ops

}