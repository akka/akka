/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.event.LoggingAdapter
import akka.stream._
import akka.japi.Pair
import akka.japi.function
import akka.stream.scaladsl
import org.reactivestreams.Processor
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.stream.stage.Stage
import akka.stream.impl.StreamLayout

object Flow {

  val factory: FlowCreate = new FlowCreate {}

  /** Adapt [[scaladsl.Flow]] for use within Java DSL */
  def adapt[I, O, M](flow: scaladsl.Flow[I, O, M]): javadsl.Flow[I, O, M] =
    new Flow(flow)

  /** Create a `Flow` which can process elements of type `T`. */
  def empty[T](): javadsl.Flow[T, T, Unit] =
    Flow.create()

  /** Create a `Flow` which can process elements of type `T`. */
  def create[T](): javadsl.Flow[T, T, Unit] =
    adapt(scaladsl.Flow[T])

  def create[I, O](processorFactory: function.Creator[Processor[I, O]]): javadsl.Flow[I, O, Unit] =
    adapt(scaladsl.Flow(() ⇒ processorFactory.create()))

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](clazz: Class[T]): javadsl.Flow[T, T, Unit] =
    create[T]()

  /**
   * A graph with the shape of a flow logically is a flow, this method makes
   * it so also in type.
   */
  def wrap[I, O, M](g: Graph[FlowShape[I, O], M]): Flow[I, O, M] =
    g match {
      case f: Flow[I, O, M] ⇒ f
      case other            ⇒ new Flow(scaladsl.Flow.wrap(other))
    }

  /**
   * Helper to create `Flow` from a pair of sink and source.
   */
  def wrap[I, O, M1, M2, M](
    sink: Graph[SinkShape[I], M1],
    source: Graph[SourceShape[O], M2],
    combine: function.Function2[M1, M2, M]): Flow[I, O, M] = new Flow(scaladsl.Flow.wrap(sink, source)(combine.apply _))
}

/** Create a `Flow` which can process elements of type `T`. */
class Flow[-In, +Out, +Mat](delegate: scaladsl.Flow[In, Out, Mat]) extends Graph[FlowShape[In, Out], Mat] {
  import scala.collection.JavaConverters._

  override def shape: FlowShape[In, Out] = delegate.shape
  private[stream] def module: StreamLayout.Module = delegate.module

  /** Converts this Flow to its Scala DSL counterpart */
  def asScala: scaladsl.Flow[In, Out, Mat] = delegate

  /**
   * Transform only the materialized value of this Flow, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): Flow[In, Out, Mat2] =
    new Flow(delegate.mapMaterializedValue(f.apply _))

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   *
   * {{{
   *     +----------------------------+
   *     | Resulting Flow             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   *
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * [[Flow#viaMat viaMat]] if a different strategy is needed.
   */
  def via[T, M](flow: Graph[FlowShape[Out, T], M]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.via(flow))

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   * {{{
   *     +----------------------------+
   *     | Resulting Flow             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting Flow.
   */
  def viaMat[T, M, M2](flow: Graph[FlowShape[Out, T], M], combine: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.viaMat(flow)(combinerToScala(combine)))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to(sink: Graph[SinkShape[Out], _]): javadsl.Sink[In, Mat] =
    new Sink(delegate.to(sink))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   */
  def toMat[M, M2](sink: Graph[SinkShape[Out], M], combine: function.Function2[Mat, M, M2]): javadsl.Sink[In, M2] =
    new Sink(delegate.toMat(sink)(combinerToScala(combine)))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableGraph]]
   */
  def join[M](flow: Graph[FlowShape[Out, In], M]): javadsl.RunnableGraph[Mat] =
    new RunnableGraphAdapter(delegate.join(flow))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableGraph]]
   */
  def joinMat[M, M2](flow: Graph[FlowShape[Out, In], M], combine: function.Function2[Mat, M, M2]): javadsl.RunnableGraph[M2] =
    new RunnableGraphAdapter(delegate.joinMat(flow)(combinerToScala(combine)))

  /**
   * Join this [[Flow]] to a [[BidiFlow]] to close off the “top” of the protocol stack:
   * {{{
   * +---------------------------+
   * | Resulting Flow            |
   * |                           |
   * | +------+        +------+  |
   * | |      | ~Out~> |      | ~~> O2
   * | | flow |        | bidi |  |
   * | |      | <~In~  |      | <~~ I2
   * | +------+        +------+  |
   * +---------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the [[BidiFlow]]’s value), use
   * [[Flow#joinMat[I2* joinMat]] if a different strategy is needed.
   */
  def join[I2, O2, Mat2](bidi: Graph[BidiShape[Out, O2, I2, In], Mat2]): Flow[I2, O2, Mat] =
    new Flow(delegate.join(bidi))

  /**
   * Join this [[Flow]] to a [[BidiFlow]] to close off the “top” of the protocol stack:
   * {{{
   * +---------------------------+
   * | Resulting Flow            |
   * |                           |
   * | +------+        +------+  |
   * | |      | ~Out~> |      | ~~> O2
   * | | flow |        | bidi |  |
   * | |      | <~In~  |      | <~~ I2
   * | +------+        +------+  |
   * +---------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * [[BidiFlow]] into the materialized value of the resulting [[Flow]].
   */
  def joinMat[I2, O2, Mat2, M](bidi: Graph[BidiShape[Out, O2, I2, In], Mat2], combine: function.Function2[Mat, Mat2, M]): Flow[I2, O2, M] =
    new Flow(delegate.joinMat(bidi)(combinerToScala(combine)))

  /**
   * Connect the `KeyedSource` to this `Flow` and then connect it to the `KeyedSink` and run it.
   *
   * The returned tuple contains the materialized values of the `KeyedSource` and `KeyedSink`,
   * e.g. the `Subscriber` of a `Source.subscriber` and `Publisher` of a `Sink.publisher`.
   *
   * @tparam T materialized type of given KeyedSource
   * @tparam U materialized type of given KeyedSink
   */
  def runWith[T, U](source: Graph[SourceShape[In], T], sink: Graph[SinkShape[Out], U], materializer: Materializer): akka.japi.Pair[T, U] = {
    val p = delegate.runWith(source, sink)(materializer)
    akka.japi.Pair(p._1.asInstanceOf[T], p._2.asInstanceOf[U])
  }

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def map[T](f: function.Function[Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.map(f.apply))

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream.
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `oncurrentModificationException` or other more subtle errors may occur.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def mapConcat[T](f: function.Function[Out, java.lang.Iterable[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapConcat { elem ⇒
      val scalaIterable = new immutable.Iterable[T] {
        import collection.JavaConverters._
        override def iterator: Iterator[T] = f(elem).iterator().asScala
      }
      scalaIterable
    })

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive.
   *
   * '''Emits when''' the Future returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream
   * backpressures or the first future is not completed
   *
   * '''Completes when''' upstream completes and all futures has been completed and all elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: function.Function[Out, Future[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsync(parallelism)(f.apply))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and each processed element will be emitted dowstream
   * as soon as it is ready, i.e. it is possible that the elements are not emitted downstream
   * in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive (even though the result of the futures
   * returned by `f` might be emitted in a different order).
   *
   * '''Emits when''' any of the Futures returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures has been completed and all elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: function.Function[Out, Future[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsyncUnordered(parallelism)(f.apply))

  /**
   * Only pass on those elements that satisfy the given predicate.
   *
   * '''Emits when''' the given predicate returns true for the element
   *
   * '''Backpressures when''' the given predicate returns true for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def filter(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.filter(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.collect(pf))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' the specified number of elements has been accumulated or upstream completed
   *
   * '''Backpressures when''' a group has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def grouped(n: Int): javadsl.Flow[In, java.util.List[Out @uncheckedVariance], Mat] =
    new Flow(delegate.grouped(n).map(_.asJava)) // TODO optimize to one step

  /**
   * Apply a sliding window over the stream and return the windows as groups of elements, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   * `step` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' enough elements have been collected within the window or upstream completed
   *
   * '''Backpressures when''' a window has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def sliding(n: Int, step: Int = 1): javadsl.Flow[In, java.util.List[Out @uncheckedVariance], Mat] =
    new Flow(delegate.sliding(n, step).map(_.asJava)) // TODO optimize to one step

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * '''Emits when''' the function scanning the element returns a new element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def scan[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.scan(zero)(f.apply))

  /**
   * Similar to `scan` but only emits its result when the upstream completes,
   * after which it also completes. Applies the given function `f` towards its current and next value,
   * yielding the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def fold[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.fold(zero)(f.apply))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted
   *
   * '''Backpressures when''' the configured time elapses since the last group has been emitted
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Flow[In, java.util.List[Out @uncheckedVariance], Mat] =
    new Flow(delegate.groupedWithin(n, d).map(_.asJava)) // TODO optimize to one step

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   *
   * '''Emits when''' the specified number of elements has been dropped already
   *
   * '''Backpressures when''' the specified number of elements has been dropped and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def drop(n: Long): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   *
   * '''Emits when''' the specified time elapsed and a new upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def dropWithin(d: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.dropWithin(d))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   */
  def takeWhile(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] = new Flow(delegate.takeWhile(p.test))

  /**
   * Discard elements at the beginning of the stream while predicate is true.
   * All elements will be taken after predicate returns false first time.
   *
   * '''Emits when''' predicate returned false and for all following stream elements
   *
   * '''Backpressures when''' predicate returned false and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def dropWhile(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] = new Flow(delegate.dropWhile(p.test))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def recover[T >: Out](pf: PartialFunction[Throwable, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.recover(pf))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   */
  def take(n: Long): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   *
   * '''Emits when''' an upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or timer fires
   *
   * '''Cancels when''' downstream cancels or timer fires
   */
  def takeWithin(d: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.takeWithin(d))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   */
  def conflate[S](seed: function.Function[Out, S], aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.conflate(seed.apply)(aggregate.apply))

  /**
   * Allows a faster downstream to progress independently of a slower publisher by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * Expand does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `seed` or `extrapolate` functions will complete the stream with failure.
   *
   * '''Emits when''' downstream stops backpressuring
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: function.Function[Out, S], extrapolate: function.Function[S, akka.japi.Pair[U, S]]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.expand(seed(_))(s ⇒ {
      val p = extrapolate(s)
      (p.first, p.second)
    }))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * '''Emits when''' downstream stops backpressuring and there is a pending element in the buffer
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[U](mkStage: function.Creator[Stage[Out, U]]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.transform(() ⇒ mkStage.create()))

  /**
   * Takes up to `n` elements from the stream (less than `n` only if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements has been seen, and therefore the substream
   *    has not yet been emitted
   *  - the tail substream signals the error after the prefix and tail has been emitted by the main stream
   *    (at that point the main stream has already completed)
   *
   * '''Emits when''' the configured number of prefix elements are available. Emits this prefix, and the rest
   * as a substream
   *
   * '''Backpressures when''' downstream backpressures or substream backpressures
   *
   * '''Completes when''' prefix elements has been consumed and substream has been consumed
   *
   * '''Cancels when''' downstream cancels or substream cancels
   */
  def prefixAndTail(n: Int): javadsl.Flow[In, akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance, Unit]], Mat] =
    new Flow(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ akka.japi.Pair(taken.asJava, tail.asJava) })

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
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element for which the grouping function returns a group that has not yet been created.
   * Emits the new group
   *
   * '''Backpressures when''' there is an element pending for a group whose substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and all substreams cancel
   *
   */
  def groupBy[K](f: function.Function[Out, K]): javadsl.Flow[In, akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance, Unit]], Mat] =
    new Flow(delegate.groupBy(f.apply).map { case (k, p) ⇒ akka.japi.Pair(k, p.asJava) }) // TODO optimize to one step

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
   *
   * In case the *first* element of the stream matches the predicate, the first
   * substream emitted by splitWhen will start from that element. For example:
   *
   * {{{
   * true, false, false // first substream starts from the split-by element
   * true, false        // subsequent substreams operate the same way
   * }}}
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element for which the provided predicate is true, opening and emitting
   * a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel
   */
  def splitWhen(p: function.Predicate[Out]): javadsl.Flow[In, Source[Out, Unit], Mat] =
    new Flow(delegate.splitWhen(p.test).map(_.asJava))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true. This means that for the following series of predicate values,
   * three substreams will be produced with lengths 2, 2, and 3:
   *
   * {{{
   * false, true,        // elements go into first substream
   * false, true,        // elements go into second substream
   * false, false, true  // elements go into third substream
   * }}}
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element passes through. When the provided predicate is true it emitts the element
   * and opens a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel
   *
   * See also [[Flow.splitWhen]].
   */
  def splitAfter[U >: Out](p: function.Predicate[Out]): javadsl.Flow[In, Source[Out, Unit], Mat] =
    new Flow(delegate.splitAfter(p.test).map(_.asJava))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   *
   * '''Emits when''' (Concat) the current consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.flatten(strategy))

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced. Note that the Source is materialized
   * together with this Flow and just kept from producing elements by asserting
   * back-pressure until its time comes.
   *
   * The resulting Flow’s materialized value is a Pair containing both materialized
   * values (of this Flow and that Source).
   */
  def concat[M](source: Graph[SourceShape[Out @uncheckedVariance], M]): javadsl.Flow[In, Out, Mat @uncheckedVariance Pair M] =
    new Flow(delegate.concat(source).mapMaterializedValue(p ⇒ Pair(p._1, p._2)))

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced. Note that the Source is materialized
   * together with this Flow and just kept from producing elements by asserting
   * back-pressure until its time comes.
   */
  def concatMat[M, M2](source: Graph[SourceShape[Out @uncheckedVariance], M], combine: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out, M2] =
    new Flow(delegate.concatMat(source)(combinerToScala(combine)))

  override def withAttributes(attr: Attributes): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.withAttributes(attr))

  override def named(name: String): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.named(name))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] atrribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any], log: LoggingAdapter): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.log(name, e ⇒ extract.apply(e))(log))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] atrribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any]): javadsl.Flow[In, Out, Mat] =
    this.log(name, extract, null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] atrribute on the given Flow:
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, log: LoggingAdapter): javadsl.Flow[In, Out, Mat] =
    this.log(name, javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] atrribute on the given Flow.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   */
  def log(name: String): javadsl.Flow[In, Out, Mat] =
    this.log(name, javaIdentityFunction[Out], null)

  /**
   * Converts this Flow to a [[RunnableGraph]] that materializes to a Reactive Streams [[org.reactivestreams.Processor]]
   * which implements the operations encapsulated by this Flow. Every materialization results in a new Processor
   * instance, i.e. the returned [[RunnableGraph]] is reusable.
   *
   * @return A [[RunnableGraph]] that materializes to a Processor when run() is called on it.
   */
  def toProcessor: RunnableGraph[Processor[In @uncheckedVariance, Out @uncheckedVariance]] = {
    new RunnableGraphAdapter(delegate.toProcessor)
  }
}

/**
 * Java API
 *
 * Flow with attached input and output, can be executed.
 */
trait RunnableGraph[+Mat] extends Graph[ClosedShape, Mat] {
  /**
   * Run this flow and return the materialized values of the flow.
   */
  def run(materializer: Materializer): Mat
  /**
   * Transform only the materialized value of this RunnableGraph, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): RunnableGraph[Mat2]
}

/** INTERNAL API */
private[akka] class RunnableGraphAdapter[Mat](runnable: scaladsl.RunnableGraph[Mat]) extends RunnableGraph[Mat] {
  def shape = ClosedShape
  def module = runnable.module
  override def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): RunnableGraph[Mat2] =
    new RunnableGraphAdapter(runnable.mapMaterializedValue(f.apply _))
  override def run(materializer: Materializer): Mat = runnable.run()(materializer)

  override def withAttributes(attr: Attributes): RunnableGraph[Mat] =
    new RunnableGraphAdapter(runnable.withAttributes(attr))

  override def named(name: String): RunnableGraph[Mat] =
    new RunnableGraphAdapter(runnable.named(name))
}
