/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._
import akka.stream.scaladsl2._
import scaladsl2.FlowMaterializer

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** Java API */
trait FlowOps[-In, +Out] {

  // RUN WITH //

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * The returned tuple contains the materialized values of the `Source` and `Sink`,
   * e.g. the `Subscriber` of a [[SubscriberSource]] and `Publisher` of a [[PublisherSink]].
   *
   * @tparam T materialized type of given Source
   * @tparam U materialized type of given Sink
   */
  def runWith[T, U](source: javadsl.KeyedSource[In, T], sink: javadsl.KeyedSink[Out, U], materializer: FlowMaterializer): akka.japi.Pair[T, U]

  /**
   * Connect the `Source` to this `Flow and then connect it to the `Sink` and run it.
   *
   * The returned value will contain the materialized value of the `KeyedSink`, e.g. `Publisher` of a [[PublisherSink]].
   *
   * @tparam T materialized type of given Sink
   */
  def runWith[T](source: javadsl.SimpleSource[In], sink: javadsl.KeyedSink[Out, T], materializer: FlowMaterializer): T

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * The returned value will contain the materialized value of the `KeyedSource`, e.g. `Subscriber` of a [[SubscriberSource]].
   *
   * @tparam T materialized type of given Source
   */
  def runWith[T](source: javadsl.KeyedSource[In, T], sink: javadsl.SimpleSink[Out], materializer: FlowMaterializer): T

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * As both `Source` and `Sink` are "simple", no value is returned from this `runWith` overload.
   */
  def runWith(source: javadsl.SimpleSource[In], sink: javadsl.SimpleSink[Out], materializer: FlowMaterializer): Unit

  // COMMON OPS //

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: japi.Function[Out, T]): javadsl.Flow[In, T]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: japi.Function[Out, java.util.List[T]]): javadsl.Flow[In, T]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](f: japi.Function[Out, Future[T]]): javadsl.Flow[In, T]

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
  def mapAsyncUnordered[T](f: japi.Function[Out, Future[T]]): javadsl.Flow[In, T]

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: japi.Predicate[Out]): javadsl.Flow[In, Out]

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Flow[In, T]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): javadsl.Flow[In, java.util.List[Out @uncheckedVariance]]

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
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Flow[In, java.util.List[Out @uncheckedVariance]]

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): javadsl.Flow[In, Out]

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): javadsl.Flow[In, Out]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   */
  def take(n: Int): javadsl.Flow[In, Out]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): javadsl.Flow[In, Out]

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
  def conflate[S](seed: japi.Function[Out, S], aggregate: japi.Function2[S, Out, S]): javadsl.Flow[In, S]

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
  def expand[S, U](seed: japi.Function[Out, S], extrapolate: japi.Function[S, akka.japi.Pair[U, S]]): javadsl.Flow[In, U]

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[OverflowStrategy]] it might drop elements or backpressure the upstream if there is no
   * space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out]

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
  def transform[U](name: String, mkTransformer: japi.Creator[Transformer[Out, U]]): javadsl.Flow[In, U]

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
  def timerTransform[U](name: String, mkTransformer: japi.Creator[TimerTransformer[Out, U]]): javadsl.Flow[In, U]

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail(n: Int): javadsl.Flow[In, akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance]]]

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
  def groupBy[K](f: japi.Function[Out, K]): javadsl.Flow[In, akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance]]]

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
  def splitWhen(p: japi.Predicate[Out]): javadsl.Flow[In, Source[Out]]

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): javadsl.Flow[In, U]

}

/** Java API */
trait SourceOps[+Out] {

  // RUN WITH //

  /**
   * Connect the `Sink` to this `Source` and then connect it to the `Source` and run it.
   *
   * The returned value the materialized values of the `Source` and `Sink`, e.g. the `Subscriber` of a
   * [[akka.stream.scaladsl2.SubscriberSource]] and and `Publisher` of a [[akka.stream.scaladsl2.PublisherSink]].
   *
   * @tparam M materialized type of given Source
   */
  def runWith[M](sink: javadsl.KeyedSink[Out, M], materializer: FlowMaterializer): M

  /**
   * Connect this `Source` to a `Source` and run it.
   */
  def runWith(sink: javadsl.SimpleSink[Out], materializer: FlowMaterializer): Unit

  // COMMON OPS //

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: japi.Function[Out, T]): javadsl.Source[T]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: japi.Function[Out, java.util.List[T]]): javadsl.Source[T]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](f: japi.Function[Out, Future[T]]): javadsl.Source[T]

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
  def mapAsyncUnordered[T](f: japi.Function[Out, Future[T]]): javadsl.Source[T]

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: japi.Predicate[Out]): javadsl.Source[Out]

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Source[T]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): javadsl.Source[java.util.List[Out @uncheckedVariance]]

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
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Source[java.util.List[Out @uncheckedVariance]]

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): javadsl.Source[Out]

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): javadsl.Source[Out]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   */
  def take(n: Int): javadsl.Source[Out]

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): javadsl.Source[Out]

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
  def conflate[S](seed: japi.Function[Out, S], aggregate: japi.Function2[S, Out, S]): javadsl.Source[S]

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
  def expand[S, U](seed: japi.Function[Out, S], extrapolate: japi.Function[S, akka.japi.Pair[U, S]]): javadsl.Source[U]

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[OverflowStrategy]] it might drop elements or backpressure the upstream if there is no
   * space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Source[Out]

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
  def transform[U](name: String, mkTransformer: japi.Creator[Transformer[Out, U]]): javadsl.Source[U]

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
  def timerTransform[U](name: String, mkTransformer: japi.Creator[TimerTransformer[Out, U]]): javadsl.Source[U]

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail(n: Int): javadsl.Source[akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance]]]

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
  def groupBy[K](f: japi.Function[Out, K]): javadsl.Source[akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance]]]

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
  def splitWhen(p: japi.Predicate[Out]): javadsl.Source[javadsl.Source[Out]]

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): javadsl.Source[U]

}

/** Java API */
trait SinkOps[-In] {

  // RUN WITH //

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Source` and run it.
   * The returned tuple contains the materialized values of the `Source` and `Sink`, e.g. the `Subscriber` of a
   * [[akka.stream.scaladsl2.SubscriberSource]] and and `Publisher` of a [[akka.stream.scaladsl2.PublisherSink]].
   *
   * @tparam T materialized type of given Source
   */
  def runWith[T](source: javadsl.KeyedSource[In, T], materializer: FlowMaterializer): T

  /**
   * Connect this `Source` to a `Source` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a [[akka.stream.scaladsl2.PublisherSink]].
   */
  def runWith(source: javadsl.SimpleSource[In], materializer: FlowMaterializer): Unit
}