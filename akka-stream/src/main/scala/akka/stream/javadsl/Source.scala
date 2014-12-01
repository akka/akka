/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import java.util.concurrent.Callable
import akka.actor.ActorRef
import akka.actor.Props
import akka.japi.Util
import akka.stream._
import akka.stream.scaladsl.PropsSource
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds
import scala.language.implicitConversions
import akka.stream.stage.Stage

/** Java API */
object Source {

  import scaladsl.JavaConverters._

  /** Adapt [[scaladsl.Source]] for use within JavaDSL */
  def adapt[O](source: scaladsl.Source[O]): Source[O] =
    new Source(source)

  /**
   * Create a `Source` with no elements, i.e. an empty stream that is completed immediately
   * for every connected `Sink`.
   */
  def empty[O](): Source[O] =
    new Source(scaladsl.Source.empty())

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def from[O](publisher: Publisher[O]): javadsl.Source[O] =
    new Source(scaladsl.Source.apply(publisher))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage:
   *
   * {{{
   * List<Integer> data = new ArrayList<Integer>();
   * data.add(1);
   * data.add(2);
   * data.add(3);
   * Source.from(data.iterator());
   * }}}
   *
   * Start a new `Source` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def from[O](f: japi.Creator[java.util.Iterator[O]]): javadsl.Source[O] =
    new Source(scaladsl.Source(() ⇒ f.create().asScala))

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage:
   * {{{
   * List<Integer> data = new ArrayList<Integer>();
   * data.add(1);
   * data.add(2);
   * data.add(3);
   * Source.fom(data);
   * }}}
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def from[O](iterable: java.lang.Iterable[O]): javadsl.Source[O] =
    new Source(scaladsl.Source(akka.stream.javadsl.japi.Util.immutableIterable(iterable)))

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def from[O](future: Future[O]): javadsl.Source[O] =
    new Source(scaladsl.Source(future))

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def from[O](initialDelay: FiniteDuration, interval: FiniteDuration, tick: Callable[O]): javadsl.Source[O] =
    new Source(scaladsl.Source(initialDelay, interval, () ⇒ tick.call()))

  /**
   * Creates a `Source` by using a [[FlowGraphBuilder]] from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSink`.
   */
  def from[T](graph: PartialFlowGraph, block: japi.Function[FlowGraphBuilder, UndefinedSink[T]]): Source[T] =
    new Source(scaladsl.Source(graph.asScala)(x ⇒ block.apply(x.asJava).asScala))

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorPublisher]].
   */
  def from[T](props: Props): KeyedSource[T, ActorRef] =
    new KeyedSource(scaladsl.Source.apply(props))

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def singleton[T](element: T): Source[T] =
    new Source(scaladsl.Source.singleton(element))

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T] =
    new Source(scaladsl.Source.failed(cause))

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def subscriber[T](): KeyedSource[Subscriber[T], T] =
    new KeyedSource(scaladsl.Source.subscriber)

  /**
   * Concatenates two sources so that the first element
   * emitted by the second source is emitted after the last element of the first
   * source.
   */
  def concat[T](first: Source[T], second: Source[T]): Source[T] =
    new KeyedSource(scaladsl.Source.concat(first.asScala, second.asScala))
}

/**
 * Java API
 *
 * A `Source` is a set of stream processing steps that has one open output and an attached input.
 * Can be used as a `Publisher`
 */
class Source[+Out](delegate: scaladsl.Source[Out]) {
  import akka.stream.scaladsl.JavaConverters._

  import scala.collection.JavaConverters._

  /** Converts this Java DSL element to it's Scala DSL counterpart. */
  def asScala: scaladsl.Source[Out] = delegate

  /**
   * Transform this [[Source]] by appending the given processing stages.
   */
  def via[T](flow: javadsl.Flow[Out, T]): javadsl.Source[T] =
    new Source(delegate.via(flow.asScala))

  /**
   * Connect this [[Source]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to(sink: javadsl.Sink[Out]): javadsl.RunnableFlow =
    new RunnableFlowAdapter(delegate.to(sink.asScala))

  /**
   * Connect this `Source` to a `KeyedSink` and run it.
   *
   * The returned value is the materialized value of the `Sink`, e.g. the `Publisher` of a `Sink.publisher()`.
   *
   * @tparam S materialized type of the given Sink
   */
  def runWith[S](sink: KeyedSink[Out, S], materializer: FlowMaterializer): S =
    asScala.runWith(sink.asScala)(materializer).asInstanceOf[S]

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a `Sink.publisher()`.
   */
  def runWith(sink: Sink[Out], materializer: FlowMaterializer): Unit =
    delegate.to(sink.asScala).run()(materializer)

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U](zero: U, f: japi.Function2[U, Out, U], materializer: FlowMaterializer): Future[U] =
    runWith(Sink.fold(zero, f), materializer)

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   */
  def concat[Out2 >: Out](second: Source[Out2]): Source[Out2] =
    delegate.concat(second.asScala).asJava

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream.
   */
  def foreach(f: japi.Procedure[Out], materializer: FlowMaterializer): Future[Unit] =
    runWith(Sink.foreach(f), materializer)

  // COMMON OPS //

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: japi.Function[Out, T]): javadsl.Source[T] =
    new Source(delegate.map(f.apply))

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: japi.Function[Out, java.util.List[T]]): javadsl.Source[T] =
    new Source(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](f: japi.Function[Out, Future[T]]): javadsl.Source[T] =
    new Source(delegate.mapAsync(f.apply))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and each processed element will be emitted dowstream
   * as soon as it is ready, i.e. it is possible that the elements are not emitted downstream
   * in the same order as from upstream.
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](f: japi.Function[Out, Future[T]]): javadsl.Source[T] =
    new Source(delegate.mapAsyncUnordered(f.apply))

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: japi.Predicate[Out]): javadsl.Source[Out] =
    new Source(delegate.filter(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Source[T] =
    new Source(delegate.collect(pf))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * @param n must be positive, otherwise [[IllegalArgumentException]] is thrown.
   */
  def grouped(n: Int): javadsl.Source[java.util.List[Out @uncheckedVariance]] =
    new Source(delegate.grouped(n).map(_.asJava))

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * yielding the next current value.
   */
  def scan[T](zero: T)(f: japi.Function2[T, Out, T]): javadsl.Source[T] =
    new Source(delegate.scan(zero)(f.apply))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * @param n must be positive, and `d` must be greater than 0 seconds, otherwise [[IllegalArgumentException]] is thrown.
   */
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Source[java.util.List[Out @uncheckedVariance]] =
    new Source(delegate.groupedWithin(n, d).map(_.asJava)) // FIXME optimize to one step

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): javadsl.Source[Out] =
    new Source(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): javadsl.Source[Out] =
    new Source(delegate.dropWithin(d))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * @param n if `n` is zero or negative the stream will be completed without producing any elements.
   */
  def take(n: Int): javadsl.Source[Out] =
    new Source(delegate.take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): javadsl.Source[Out] =
    new Source(delegate.takeWithin(d))

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
  def conflate[S](seed: japi.Function[Out, S], aggregate: japi.Function2[S, Out, S]): javadsl.Source[S] =
    new Source(delegate.conflate(seed.apply)(aggregate.apply))

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
  def expand[S, U](seed: japi.Function[Out, S], extrapolate: japi.Function[S, akka.japi.Pair[U, S]]): javadsl.Source[U] =
    new Source(delegate.expand(seed(_))(s ⇒ {
      val p = extrapolate(s)
      (p.first, p.second)
    }))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Source[Out] =
    new Source(delegate.buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[U](mkStage: japi.Creator[Stage[Out, U]]): javadsl.Source[U] =
    new Source(delegate.transform(() ⇒ mkStage.create()))

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail(n: Int): javadsl.Source[akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance]]] =
    new Source(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ akka.japi.Pair(taken.asJava, tail.asJava) })

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * it is emitted to the downstream subscriber together with a fresh
   * flow that will eventually produce all the elements of the substream
   * for that key. Not consuming the elements from the created streams will
   * stop this processor from processing more elements, therefore you must take
   * care to unblock (or cancel) all of the produced streams even if you want
   * to consume only one of them.
   */
  def groupBy[K](f: japi.Function[Out, K]): javadsl.Source[akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance]]] =
    new Source(delegate.groupBy(f.apply).map { case (k, p) ⇒ akka.japi.Pair(k, p.asJava) }) // FIXME optimize to one step

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
  def splitWhen(p: japi.Predicate[Out]): javadsl.Source[javadsl.Source[Out]] =
    new Source(delegate.splitWhen(p.test).map(_.asJava))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): javadsl.Source[U] =
    new Source(delegate.flatten(strategy))

  /**
   * Add a key that will have a value available after materialization.
   * The key can only use other keys if they have been added to the source
   * before this key. This also includes the keyed source if applicable.
   */
  def withKey[T](key: javadsl.Key[T]): javadsl.Source[Out] =
    new Source(delegate.withKey(key.asScala))

  /**
   * Applies given [[OperationAttributes]] to a given section.
   */
  def section[O](attributes: OperationAttributes, section: japi.Function[javadsl.Source[Out], javadsl.Source[O]]): javadsl.Source[O] =
    new Source(delegate.section(attributes.asScala) {
      val scalaToJava = (source: scaladsl.Source[Out]) ⇒ new javadsl.Source[Out](source)
      val javaToScala = (source: javadsl.Source[O]) ⇒ source.asScala
      scalaToJava andThen section.apply andThen javaToScala
    })
}

/**
 * Java API
 *
 * A `Source` that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this source (could be a Subscriber, a Future/Promise, etc.).
 */
final class KeyedSource[+Out, T](delegate: scaladsl.Source[Out]) extends Source[Out](delegate) {
  override def asScala: scaladsl.KeyedActorFlowSource[Out] = super.asScala.asInstanceOf[scaladsl.KeyedActorFlowSource[Out]]
}
