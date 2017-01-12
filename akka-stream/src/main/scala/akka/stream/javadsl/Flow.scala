/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import akka.{ Done, NotUsed }
import akka.event.LoggingAdapter
import akka.japi.{ Pair, function }
import akka.stream.impl.{ ConstantFun, StreamLayout }
import akka.stream._
import org.reactivestreams.Processor

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.FiniteDuration
import akka.japi.Util
import java.util.Comparator
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._

object Flow {

  private[this] val _identity = new javadsl.Flow(scaladsl.Flow[Any])

  /** Create a `Flow` which can process elements of type `T`. */
  def create[T](): javadsl.Flow[T, T, NotUsed] = fromGraph(scaladsl.Flow[T])

  def fromProcessor[I, O](processorFactory: function.Creator[Processor[I, O]]): javadsl.Flow[I, O, NotUsed] =
    new Flow(scaladsl.Flow.fromProcessor(() ⇒ processorFactory.create()))

  def fromProcessorMat[I, O, Mat](processorFactory: function.Creator[Pair[Processor[I, O], Mat]]): javadsl.Flow[I, O, Mat] =
    new Flow(scaladsl.Flow.fromProcessorMat { () ⇒
      val javaPair = processorFactory.create()
      (javaPair.first, javaPair.second)
    })

  /**
   * Creates a [Flow] which will use the given function to transform its inputs to outputs. It is equivalent
   * to `Flow.create[T].map(f)`
   */
  def fromFunction[I, O](f: function.Function[I, O]): javadsl.Flow[I, O, NotUsed] =
    Flow.create[I]().map(f)

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](clazz: Class[T]): javadsl.Flow[T, T, NotUsed] = create[T]()

  /**
   * A graph with the shape of a flow logically is a flow, this method makes it so also in type.
   */
  def fromGraph[I, O, M](g: Graph[FlowShape[I, O], M]): Flow[I, O, M] =
    g match {
      case f: Flow[I, O, M]                          ⇒ f
      case f: scaladsl.Flow[I, O, M] if f.isIdentity ⇒ _identity.asInstanceOf[Flow[I, O, M]]
      case other                                     ⇒ new Flow(scaladsl.Flow.fromGraph(other))
    }

  /**
   * Helper to create `Flow` from a `Sink`and a `Source`.
   */
  def fromSinkAndSource[I, O](sink: Graph[SinkShape[I], _], source: Graph[SourceShape[O], _]): Flow[I, O, NotUsed] =
    new Flow(scaladsl.Flow.fromSinkAndSourceMat(sink, source)(scaladsl.Keep.none))

  /**
   * Helper to create `Flow` from a `Sink`and a `Source`.
   */
  def fromSinkAndSourceMat[I, O, M1, M2, M](
    sink: Graph[SinkShape[I], M1], source: Graph[SourceShape[O], M2],
    combine: function.Function2[M1, M2, M]): Flow[I, O, M] =
    new Flow(scaladsl.Flow.fromSinkAndSourceMat(sink, source)(combinerToScala(combine)))
}

/** Create a `Flow` which can process elements of type `T`. */
final class Flow[-In, +Out, +Mat](delegate: scaladsl.Flow[In, Out, Mat]) extends Graph[FlowShape[In, Out], Mat] {
  import scala.collection.JavaConverters._

  override def shape: FlowShape[In, Out] = delegate.shape
  def module: StreamLayout.Module = delegate.module

  override def toString: String = delegate.toString

  /** Converts this Flow to its Scala DSL counterpart */
  def asScala: scaladsl.Flow[In, Out, Mat] = delegate

  /**
   * Transform only the materialized value of this Flow, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): Flow[In, Out, Mat2] =
    new Flow(delegate.mapMaterializedValue(f.apply _))

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
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * `viaMat` if a different strategy is needed.
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
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def viaMat[T, M, M2](flow: Graph[FlowShape[Out, T], M], combine: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.viaMat(flow)(combinerToScala(combine)))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting Sink             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | flow | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[Sink]] will be the materialized
   * value of the current flow (ignoring the given Sink’s value), use
   * `toMat` if a different strategy is needed.
   */
  def to(sink: Graph[SinkShape[Out], _]): javadsl.Sink[In, Mat] =
    new Sink(delegate.to(sink))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting Sink             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | flow | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Sink into the materialized value of the resulting Sink.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def toMat[M, M2](sink: Graph[SinkShape[Out], M], combine: function.Function2[Mat, M, M2]): javadsl.Sink[In, M2] =
    new Sink(delegate.toMat(sink)(combinerToScala(combine)))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableGraph]].
   * {{{
   * +------+        +-------+
   * |      | ~Out~> |       |
   * | this |        | other |
   * |      | <~In~  |       |
   * +------+        +-------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * `joinMat` if a different strategy is needed.
   */
  def join[M](flow: Graph[FlowShape[Out, In], M]): javadsl.RunnableGraph[Mat] =
    RunnableGraph.fromGraph(delegate.join(flow))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableGraph]]
   * {{{
   * +------+        +-------+
   * |      | ~Out~> |       |
   * | this |        | other |
   * |      | <~In~  |       |
   * +------+        +-------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Flow into the materialized value of the resulting Flow.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def joinMat[M, M2](flow: Graph[FlowShape[Out, In], M], combine: function.Function2[Mat, M, M2]): javadsl.RunnableGraph[M2] =
    RunnableGraph.fromGraph(delegate.joinMat(flow)(combinerToScala(combine)))

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
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def joinMat[I2, O2, Mat2, M](bidi: Graph[BidiShape[Out, O2, I2, In], Mat2], combine: function.Function2[Mat, Mat2, M]): Flow[I2, O2, M] =
    new Flow(delegate.joinMat(bidi)(combinerToScala(combine)))

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * The returned tuple contains the materialized values of the `Source` and `Sink`,
   * e.g. the `Subscriber` of a `Source.asSubscriber` and `Publisher` of a `Sink.asPublisher`.
   *
   * @tparam T materialized type of given Source
   * @tparam U materialized type of given Sink
   */
  def runWith[T, U](source: Graph[SourceShape[In], T], sink: Graph[SinkShape[Out], U], materializer: Materializer): akka.japi.Pair[T, U] = {
    val (som, sim) = delegate.runWith(source, sink)(materializer)
    akka.japi.Pair(som, sim)
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
   * `ConcurrentModificationException` or other more subtle errors may occur.
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
   * '''Completes when''' upstream completes and all remaining elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def mapConcat[T](f: function.Function[Out, java.lang.Iterable[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapConcat { elem ⇒ Util.immutableSeq(f(elem)) })

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream. The transformation is meant to be stateful,
   * which is enabled by creating the transformation function anew for every materialization —
   * the returned function will typically close over mutable objects to store state between
   * invocations. For the stateless variant see [[#mapConcat]].
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
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
  def statefulMapConcat[T](f: function.Creator[function.Function[Out, java.lang.Iterable[T]]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.statefulMapConcat { () ⇒
      val fun = f.create()
      elem ⇒ Util.immutableSeq(fun(elem))
    })

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsync``.
   * These CompletionStages may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive.
   *
   * '''Emits when''' the CompletionStage returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream
   * backpressures or the first future is not completed
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsync(parallelism)(x ⇒ f(x).toScala))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsyncUnordered``.
   * Each processed element will be emitted downstream as soon as it is ready, i.e. it is possible
   * that the elements are not emitted downstream in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive (even though the result of the futures
   * returned by `f` might be emitted in a different order).
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsyncUnordered(parallelism)(x ⇒ f(x).toScala))

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
   * Only pass on those elements that NOT satisfy the given predicate.
   *
   * '''Emits when''' the given predicate returns false for the element
   *
   * '''Backpressures when''' the given predicate returns false for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filterNot(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.filterNot(p.test))

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
   * Ensure stream boundedness by limiting the number of elements from upstream.
   * If the number of incoming elements exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
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
   * '''Errors when''' the total number of incoming element exceeds max
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limit(n: Long): javadsl.Flow[In, Out, Mat] = new Flow(delegate.limit(n))

  /**
   * Ensure stream boundedness by evaluating the cost of incoming elements
   * using a cost function. Exactly how many elements will be allowed to travel downstream depends on the
   * evaluated cost of each element. If the accumulated cost exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
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
   * '''Errors when''' when the accumulated cost exceeds max
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limitWeighted(n: Long)(costFn: function.Function[Out, Long]): javadsl.Flow[In, Out, Mat] = {
    new Flow(delegate.limitWeighted(n)(costFn.apply))
  }

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
   * Similar to `scan` but with a asynchronous function,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting a `Future` that resolves to the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Resume]] current value starts at the previous
   * current value, or zero when it doesn't have one, and the stream will continue.
   *
   * '''Emits when''' the future returned by f` completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and the last future returned by `f` completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.scan]]
   */
  def scanAsync[T](zero: T)(f: function.Function2[T, Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.scanAsync(zero) { (out, in) ⇒ f(out, in).toScala })

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
   * Similar to `fold` but with an asynchronous function.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the function `f` returns a failure and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
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
  def foldAsync[T](zero: T)(f: function.Function2[T, Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] = new Flow(delegate.foldAsync(zero) { (out, in) ⇒ f(out, in).toScala })

  /**
   * Similar to `fold` but uses first element as zero element.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce stage will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def reduce(f: function.Function2[Out, Out, Out @uncheckedVariance]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.reduce(f.apply))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * In case you want to only prepend or only append an element (yet still use the `intercept` feature
   * to inject a separator between elements, you may want to use the following pattern instead of the 3-argument
   * version of intersperse (See [[Source.concat]] for semantics details):
   *
   * {{{
   * Source.single(">> ").concat(flow.intersperse(","))
   * flow.intersperse(",").concat(Source.single("END"))
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse[T >: Out](start: T, inject: T, end: T): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.intersperse(start, inject, end))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse[T >: Out](inject: T): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.intersperse(inject))

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
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[akka.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `withAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements have been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param of time to shift all messages
   * @param strategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def delay(of: FiniteDuration, strategy: DelayOverflowStrategy): Flow[In, Out, Mat] =
    new Flow(delegate.delay(of, strategy))

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
   * returns false for the first time, including the first failed element iff inclusive is true
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  def takeWhile(p: function.Predicate[Out], inclusive: Boolean = false): javadsl.Flow[In, Out, Mat] = new Flow(delegate.takeWhile(p.test, inclusive))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time, including the first failed element iff inclusive is true
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  def takeWhile(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] = takeWhile(p, false)

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
   * Throwing an exception inside `recover` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  def recover[T >: Out](pf: PartialFunction[Throwable, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.recover(pf))

  /**
   * While similar to [[recover]] this stage can be used to transform an error signal to a different one *without* logging
   * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
   * would log the `t2` error.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Similarily to [[recover]] throwing an exception inside `mapError` _will_ be logged.
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
  def mapError(pf: PartialFunction[Throwable, Throwable]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.mapError(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWith` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @deprecated("Use recoverWithRetries instead.", "2.4.4")
  def recoverWith[T >: Out](pf: PartialFunction[Throwable, _ <: Graph[SourceShape[T], NotUsed]]): javadsl.Flow[In, T, Mat @uncheckedVariance] =
    new Flow(delegate.recoverWith(pf))

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all. Passing in -1 will behave exactly the same as  `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWithRetries` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   * @param attempts Maximum number of retries or -1 to retry indefinitely
   * @param pf Receives the failure cause and returns the new Source to be materialized if any
   * @throws IllegalArgumentException if `attempts` is a negative number other than -1
   */
  def recoverWithRetries[T >: Out](attempts: Int, pf: PartialFunction[Throwable, _ <: Graph[SourceShape[T], NotUsed]]): javadsl.Flow[In, T, Mat @uncheckedVariance] =
    new Flow(delegate.recoverWithRetries(attempts, pf))

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
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
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
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  def takeWithin(d: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.takeWithin(d))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate allows to derive a seed from the first element and change the aggregated type to be
   * different than the input type. See [[Flow.conflate]] for a simpler version that does not change types.
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
   * see also [[Flow.conflate]] [[Flow.batch]] [[Flow.batchWeighted]]
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   */
  def conflateWithSeed[S](seed: function.Function[Out, S], aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.conflateWithSeed(seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate does not change the output type of the stream. See [[Flow.conflateWithSeed]] for a
   * more flexible version that can take a seed function and transform elements while rolling up.
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
   * see also [[Flow.conflateWithSeed]] [[Flow.batch]] [[Flow.batchWeighted]]
   *
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   */
  def conflate[O2 >: Out](aggregate: function.Function2[O2, O2, O2]): javadsl.Flow[In, O2, Mat] =
    new Flow(delegate.conflate(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might store received elements in
   * an array up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * '''Emits when''' downstream stops backpressuring and there is an aggregated element available
   *
   * '''Backpressures when''' there are `max` batched elements and 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[Flow.conflate]], [[Flow.batchWeighted]]
   *
   * @param max maximum number of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new aggregate
   */
  def batch[S](max: Long, seed: function.Function[Out, S], aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.batch(max, seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might concatenate `ByteString`
   * elements up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Batching will apply for all elements, even if a single element cost is greater than the total allowed limit.
   * In this case, previous batched elements will be emitted, then the "heavy" element will be emitted (after
   * being applied with the `seed` function) without batching further elements with it, and then the rest of the
   * incoming elements are batched.
   *
   * '''Emits when''' downstream stops backpressuring and there is a batched element available
   *
   * '''Backpressures when''' there are `max` weighted batched elements + 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[Flow.conflate]], [[Flow.batch]]
   *
   * @param max maximum weight of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param costFn a function to compute a single element weight
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new batch
   */
  def batchWeighted[S](max: Long, costFn: function.Function[Out, Long], seed: function.Function[Out, S], aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.batchWeighted(max, costFn.apply, seed.apply)(aggregate.apply))

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
   * '''Backpressures when''' downstream backpressures or iterator runs empty
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[U](extrapolate: function.Function[Out, java.util.Iterator[U]]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.expand(in ⇒ extrapolate(in).asScala))

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
   * '''Completes when''' upstream completes and buffered elements have been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.buffer(size, overflowStrategy))

  /**
   * Takes up to `n` elements from the stream (less than `n` if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements have been seen, and therefore the substream
   *    has not yet been emitted
   *  - the tail substream signals the error after the prefix and tail has been emitted by the main stream
   *    (at that point the main stream has already completed)
   *
   * '''Emits when''' the configured number of prefix elements are available. Emits this prefix, and the rest
   * as a substream
   *
   * '''Backpressures when''' downstream backpressures or substream backpressures
   *
   * '''Completes when''' prefix elements have been consumed and substream has been consumed
   *
   * '''Cancels when''' downstream cancels or substream cancels
   */
  def prefixAndTail(n: Int): javadsl.Flow[In, akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance, NotUsed]], Mat] =
    new Flow(delegate.prefixAndTail(n).map { case (taken, tail) ⇒ akka.japi.Pair(taken.asJava, tail.asJava) })

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * a new substream is opened and subsequently fed with all elements belonging to
   * that key.
   *
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this combinator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `groupBy`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * Function `f`  MUST NOT return `null`. This will throw exception and trigger supervision decision mechanism.
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
   * @param maxSubstreams configures the maximum number of substreams (keys)
   *        that are supported; if more distinct keys are encountered then the stream fails
   */
  def groupBy[K](maxSubstreams: Int, f: function.Function[Out, K]): SubFlow[In, Out @uncheckedVariance, Mat] =
    new SubFlow(delegate.groupBy(maxSubstreams, f.apply))

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
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this combinator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `splitWhen`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
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
   * '''Cancels when''' downstream cancels and substreams cancel on `SubstreamCancelStrategy.drain()`, downstream
   * cancels or any substream cancels on `SubstreamCancelStrategy.propagate()`
   *
   * See also [[Flow.splitAfter]].
   */
  def splitWhen(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitWhen(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it.
   *
   * @see [[#splitWhen]]
   */
  def splitWhen(substreamCancelStrategy: SubstreamCancelStrategy)(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitWhen(substreamCancelStrategy)(p.test))

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
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this combinator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `splitAfter`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element passes through. When the provided predicate is true it emits the element
   * and opens a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel on `SubstreamCancelStrategy.drain`, downstream
   * cancels or any substream cancels on `SubstreamCancelStrategy.propagate`
   *
   * See also [[Flow.splitWhen]].
   */
  def splitAfter[U >: Out](p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitAfter(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true.
   *
   * @see [[#splitAfter]]
   */
  def splitAfter(substreamCancelStrategy: SubstreamCancelStrategy)(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitAfter(substreamCancelStrategy)(p.test))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by concatenation,
   * fully consuming one Source after the other.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapConcat[T, M](f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): Flow[In, T, Mat] =
    new Flow(delegate.flatMapConcat[T, M](x ⇒ f(x)))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by merging, where at most `breadth`
   * substreams are being consumed at any given time.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapMerge[T, M](breadth: Int, f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): Flow[In, T, Mat] =
    new Flow(delegate.flatMapMerge(breadth, o ⇒ f(o)))

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Flow]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * '''Emits when''' element is available from current stream or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def concat[T >: Out, M](that: Graph[SourceShape[T], M]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.concat(that))

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Flow]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#concat]]
   */
  def concatMat[T >: Out, M, M2](that: Graph[SourceShape[T], M], matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.concatMat(that)(combinerToScala(matF)))

  /**
   * Prepend the given [[Source]] to this [[Flow]], meaning that before elements
   * are generated from this Flow, the Source's elements will be produced until it
   * is exhausted, at which point Flow elements will start being produced.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Flow]] will be pulled.
   *
   * '''Emits when''' element is available from the given [[Source]] or from current stream when the [[Source]] is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' this [[Flow]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def prepend[T >: Out, M](that: Graph[SourceShape[T], M]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.prepend(that))

  /**
   * Prepend the given [[Source]] to this [[Flow]], meaning that before elements
   * are generated from this Flow, the Source's elements will be produced until it
   * is exhausted, at which point Flow elements will start being produced.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Flow]] will be pulled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#prepend]]
   */
  def prependMat[T >: Out, M, M2](that: Graph[SourceShape[T], M], matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.prependMat(that)(combinerToScala(matF)))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes or it gets
   * cancelled.
   *
   * On errors the stage is failed regardless of source of the error.
   *
   * '''Emits when''' element is available from first stream or first stream closed without emitting any elements and an element
   *                  is available from the second stream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the primary stream completes after emitting at least one element, when the primary stream completes
   *                      without emitting and the secondary stream already has completed or when the secondary stream completes
   *
   * '''Cancels when''' downstream cancels and additionally the alternative is cancelled as soon as an element passes
   *                    by from this stream.
   */
  def orElse[T >: Out, M](secondary: Graph[SourceShape[T], M]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.orElse(secondary))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#orElse]]
   */
  def orElseMat[T >: Out, M2, M3](
    secondary: Graph[SourceShape[T], M2],
    matF:      function.Function2[Mat, M2, M3]): javadsl.Flow[In, T, M3] =
    new Flow(delegate.orElseMat(secondary)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * '''Emits when''' element is available and demand exists both from the Sink and the downstream.
   *
   * '''Backpressures when''' downstream or Sink backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def alsoTo(that: Graph[SinkShape[Out], _]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.alsoTo(that))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#alsoTo]]
   */
  def alsoToMat[M2, M3](
    that: Graph[SinkShape[Out], M2],
    matF: function.Function2[Mat, M2, M3]): javadsl.Flow[In, Out, M3] =
    new Flow(delegate.alsoToMat(that)(combinerToScala(matF)))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * Example:
   * {{{
   * Source<Integer, ?> src = Source.from(Arrays.asList(1, 2, 3))
   * Flow<Integer, Integer, ?> flow = flow.interleave(Source.from(Arrays.asList(4, 5, 6, 7)), 2)
   * src.via(flow) // 1, 2, 4, 5, 3, 6, 7
   * }}}
   *
   * After one of upstreams is complete than all the rest elements will be emitted from the second one
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * '''Emits when''' element is available from the currently consumed upstream
   *
   * '''Backpressures when''' downstream backpressures. Signal to current
   * upstream, switch to next upstream when received `segmentSize` elements
   *
   * '''Completes when''' the [[Flow]] and given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def interleave[T >: Out](that: Graph[SourceShape[T], _], segmentSize: Int): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.interleave(that, segmentSize))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * After one of upstreams is compete than all the rest elements will be emitted from the second one
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#interleave]]
   */
  def interleaveMat[T >: Out, M, M2](that: Graph[SourceShape[T], M], segmentSize: Int,
                                     matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.interleaveMat(that, segmentSize)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def merge[T >: Out](that: Graph[SourceShape[T], _]): javadsl.Flow[In, T, Mat] =
    merge(that, eagerComplete = false)

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete (eagerComplete=false) or one upstream completes (eagerComplete=true), default value is `false`
   *
   * '''Cancels when''' downstream cancels
   */
  def merge[T >: Out](that: Graph[SourceShape[T], _], eagerComplete: Boolean): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.merge(that, eagerComplete))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#merge]]
   */
  def mergeMat[T >: Out, M, M2](
    that: Graph[SourceShape[T], M],
    matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    mergeMat(that, matF, eagerComplete = false)

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#merge]]
   */
  def mergeMat[T >: Out, M, M2](
    that:          Graph[SourceShape[T], M],
    matF:          function.Function2[Mat, M, M2],
    eagerComplete: Boolean): javadsl.Flow[In, T, M2] =
    new Flow(delegate.mergeMat(that)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def mergeSorted[U >: Out, M](that: Graph[SourceShape[U], M], comp: Comparator[U]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.mergeSorted(that)(Ordering.comparatorToOrdering(comp)))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#mergeSorted]].
   */
  def mergeSortedMat[U >: Out, Mat2, Mat3](that: Graph[SourceShape[U], Mat2], comp: Comparator[U],
                                           matF: function.Function2[Mat, Mat2, Mat3]): javadsl.Flow[In, U, Mat3] =
    new Flow(delegate.mergeSortedMat(that)(combinerToScala(matF))(Ordering.comparatorToOrdering(comp)))

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples.
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zip[T](source: Graph[SourceShape[T], _]): javadsl.Flow[In, Out @uncheckedVariance Pair T, Mat] =
    zipMat(source, Keep.left)

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zip]]
   */
  def zipMat[T, M, M2](
    that: Graph[SourceShape[T], M],
    matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out @uncheckedVariance Pair T, M2] =
    this.viaMat(Flow.fromGraph(GraphDSL.create(
      that,
      new function.Function2[GraphDSL.Builder[M], SourceShape[T], FlowShape[Out, Out @uncheckedVariance Pair T]] {
        def apply(b: GraphDSL.Builder[M], s: SourceShape[T]): FlowShape[Out, Out @uncheckedVariance Pair T] = {
          val zip: FanInShape2[Out, T, Out Pair T] = b.add(Zip.create[Out, T])
          b.from(s).toInlet(zip.in1)
          FlowShape(zip.in0, zip.out)
        }
      })), matF)

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
   * into a stream of combined elements using a combiner function.
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWith[Out2, Out3](
    that:    Graph[SourceShape[Out2], _],
    combine: function.Function2[Out, Out2, Out3]): javadsl.Flow[In, Out3, Mat] =
    new Flow(delegate.zipWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
   * into a stream of combined elements using a combiner function.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipWith]]
   */
  def zipWithMat[Out2, Out3, M, M2](
    that:    Graph[SourceShape[Out2], M],
    combine: function.Function2[Out, Out2, Out3],
    matF:    function.Function2[Mat, M, M2]): javadsl.Flow[In, Out3, M2] =
    new Flow(delegate.zipWithMat[Out2, Out3, M, M2](that)(combinerToScala(combine))(combinerToScala(matF)))

  /**
   * Combine the elements of current flow into a stream of tuples consisting
   * of all elements paired with their index. Indices start at 0.
   *
   * '''Emits when''' upstream emits an element and is paired with their index
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWithIndex: Flow[In, Pair[Out @uncheckedVariance, Long], Mat] =
    new Flow(delegate.zipWithIndex.map { case (elem, index) ⇒ Pair(elem, index) })

  /**
   * If the first element has not passed through this stage before the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  def initialTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.initialTimeout(timeout))

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def completionTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.completionTimeout(timeout))

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between two emitted elements
   *
   * '''Cancels when''' downstream cancels
   */
  def idleTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.idleTimeout(timeout))

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between element emission and downstream demand.
   *
   * '''Cancels when''' downstream cancels
   */
  def backpressureTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.backpressureTimeout(timeout))

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * stage attempts to maintains a base rate of emitted elements towards the downstream.
   *
   * If the downstream backpressures then no element is injected until downstream demand arrives. Injected elements
   * do not accumulate during this period.
   *
   * Upstream elements are always preferred over injected elements.
   *
   * '''Emits when''' upstream emits an element or if the upstream was idle for the configured period
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def keepAlive[U >: Out](maxIdle: FiniteDuration, injectedElem: function.Creator[U]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.keepAlive(maxIdle, () ⇒ injectedElem.create()))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this stage set the maximum rate
   * for emitting messages. This combinator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as number of elements. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behaviour when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   * Throttler always enforces the rate limit, but in certain cases (mostly due to limited scheduler resolution) it
   * enforces a tighter bound than what was prescribed. This can be also mitigated by increasing the burst size.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int,
               mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(elements, per, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This combinator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element cost. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate.
   *
   * Parameter `mode` manages behaviour when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(cost: Int, per: FiniteDuration, maximumBurst: Int,
               costCalculation: function.Function[Out, Integer], mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(cost, per, maximumBurst, costCalculation.apply, mode))

  /**
   * Detaches upstream demand from downstream demand without detaching the
   * stream rates; in other words acts like a buffer of size 1.
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def detach: javadsl.Flow[In, Out, Mat] = new Flow(delegate.detach)

  /**
   * Materializes to `CompletionStage<Done>` that completes on getting termination message.
   * The future completes with success when received complete message from upstream or cancel
   * from downstream. It fails with the same error when received error message from
   * downstream.
   */
  def watchTermination[M]()(matF: function.Function2[Mat, CompletionStage[Done], M]): javadsl.Flow[In, Out, M] =
    new Flow(delegate.watchTermination()((left, right) ⇒ matF(left, right.toJava)))

  /**
   * Materializes to `FlowMonitor[Out]` that allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   * The `combine` function is used to combine the `FlowMonitor` with this flow's materialized value.
   */
  def monitor[M]()(combine: function.Function2[Mat, FlowMonitor[Out], M]): javadsl.Flow[In, Out, M] =
    new Flow(delegate.monitor()(combinerToScala(combine)))

  /**
   * Delays the initial element by the specified duration.
   *
   * '''Emits when''' upstream emits an element if the initial delay is already elapsed
   *
   * '''Backpressures when''' downstream backpressures or initial delay is not yet elapsed
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def initialDelay(delay: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.initialDelay(delay))

  /**
   * Change the attributes of this [[Source]] to the given ones and seal the list
   * of attributes. This means that further calls will not be able to remove these
   * attributes, but instead add new ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def withAttributes(attr: Attributes): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this Source. Further calls to `withAttributes`
   * will not remove these attributes. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def addAttributes(attr: Attributes): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.addAttributes(attr))

  /**
   * Add a ``name`` attribute to this Flow.
   */
  override def named(name: String): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.named(name))

  /**
   * Put an asynchronous boundary around this `Flow`
   */
  override def async: javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.async)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
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
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
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
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
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
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow.
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
  def log(name: String): javadsl.Flow[In, Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

  /**
   * Converts this Flow to a [[RunnableGraph]] that materializes to a Reactive Streams [[org.reactivestreams.Processor]]
   * which implements the operations encapsulated by this Flow. Every materialization results in a new Processor
   * instance, i.e. the returned [[RunnableGraph]] is reusable.
   *
   * @return A [[RunnableGraph]] that materializes to a Processor when run() is called on it.
   */
  def toProcessor: RunnableGraph[Processor[In @uncheckedVariance, Out @uncheckedVariance]] = {
    RunnableGraph.fromGraph(delegate.toProcessor)
  }
}

object RunnableGraph {
  /**
   * A graph with a closed shape is logically a runnable graph, this method makes
   * it so also in type.
   */
  def fromGraph[Mat](graph: Graph[ClosedShape, Mat]): RunnableGraph[Mat] =
    graph match {
      case r: RunnableGraph[Mat] ⇒ r
      case other                 ⇒ new RunnableGraphAdapter[Mat](scaladsl.RunnableGraph.fromGraph(graph))
    }

  /** INTERNAL API */
  private final class RunnableGraphAdapter[Mat](runnable: scaladsl.RunnableGraph[Mat]) extends RunnableGraph[Mat] {
    def shape = ClosedShape
    def module = runnable.module

    override def toString: String = runnable.toString

    override def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): RunnableGraphAdapter[Mat2] =
      new RunnableGraphAdapter(runnable.mapMaterializedValue(f.apply _))

    override def run(materializer: Materializer): Mat = runnable.run()(materializer)

    override def withAttributes(attr: Attributes): RunnableGraphAdapter[Mat] = {
      val newRunnable = runnable.withAttributes(attr)
      if (newRunnable eq runnable) this
      else new RunnableGraphAdapter(newRunnable)
    }
  }
}
/**
 * Java API
 *
 * Flow with attached input and output, can be executed.
 */
abstract class RunnableGraph[+Mat] extends Graph[ClosedShape, Mat] {

  /**
   * Run this flow and return the materialized values of the flow.
   */
  def run(materializer: Materializer): Mat
  /**
   * Transform only the materialized value of this RunnableGraph, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): RunnableGraph[Mat2]

  override def withAttributes(attr: Attributes): RunnableGraph[Mat]

  override def addAttributes(attr: Attributes): RunnableGraph[Mat] =
    withAttributes(module.attributes and attr)

  override def named(name: String): RunnableGraph[Mat] =
    withAttributes(Attributes.name(name))
}
