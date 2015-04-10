/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._
import akka.japi.{ Util, Pair }
import akka.stream.scaladsl
import scala.annotation.unchecked.uncheckedVariance
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

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](clazz: Class[T]): javadsl.Flow[T, T, Unit] =
    create[T]()

  /**
   * A graph with the shape of a flow logically is a flow, this method makes
   * it so also in type.
   */
  def wrap[I, O, M](g: Graph[FlowShape[I, O], M]): Flow[I, O, M] = new Flow(scaladsl.Flow.wrap(g))
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
  def mapMaterialized[Mat2](f: japi.Function[Mat, Mat2]): Flow[In, Out, Mat2] =
    new Flow(delegate.mapMaterialized(f.apply _))

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   */
  def via[T, M](flow: javadsl.Flow[Out, T, M]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.via(flow.asScala))

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   */
  def via[T, M, M2](flow: javadsl.Flow[Out, T, M], combine: japi.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.viaMat(flow.asScala)(combinerToScala(combine)))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to(sink: javadsl.Sink[Out, _]): javadsl.Sink[In, Mat] =
    new Sink(delegate.to(sink.asScala))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to[M, M2](sink: javadsl.Sink[Out, M], combine: japi.Function2[Mat, M, M2]): javadsl.Sink[In, M2] =
    new Sink(delegate.toMat(sink.asScala)(combinerToScala(combine)))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableFlow]]
   */
  def join[M](flow: javadsl.Flow[Out, In, M]): javadsl.RunnableFlow[Mat] =
    new RunnableFlowAdapter(delegate.join(flow.asScala))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableFlow]]
   */
  def join[M, M2](flow: javadsl.Flow[Out, In, M], combine: japi.Function2[Mat, M, M2]): javadsl.RunnableFlow[M2] =
    new RunnableFlowAdapter(delegate.joinMat(flow.asScala)(combinerToScala(combine)))

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
  def join[I2, O2, Mat2](bidi: BidiFlow[Out, O2, I2, In, Mat2]): Flow[I2, O2, Mat] =
    new Flow(delegate.join(bidi.asScala))

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
  def join[I2, O2, Mat2, M](bidi: BidiFlow[Out, O2, I2, In, Mat2], combine: japi.Function2[Mat, Mat2, M]): Flow[I2, O2, M] =
    new Flow(delegate.joinMat(bidi.asScala)(combinerToScala(combine)))

  /**
   * Connect the `KeyedSource` to this `Flow` and then connect it to the `KeyedSink` and run it.
   *
   * The returned tuple contains the materialized values of the `KeyedSource` and `KeyedSink`,
   * e.g. the `Subscriber` of a `Source.subscriber` and `Publisher` of a `Sink.publisher`.
   *
   * @tparam T materialized type of given KeyedSource
   * @tparam U materialized type of given KeyedSink
   */
  def runWith[T, U](source: javadsl.Source[In, T], sink: javadsl.Sink[Out, U], materializer: FlowMaterializer): akka.japi.Pair[T, U] = {
    val p = delegate.runWith(source.asScala, sink.asScala)(materializer)
    akka.japi.Pair(p._1.asInstanceOf[T], p._2.asInstanceOf[U])
  }

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: japi.Function[Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.map(f.apply))

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: japi.Function[Out, java.util.List[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: japi.Function[Out, Future[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsync(parallelism, f.apply))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and each processed element will be emitted dowstream
   * as soon as it is ready, i.e. it is possible that the elements are not emitted downstream
   * in the same order as received from upstream.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: japi.Function[Out, Future[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsyncUnordered(parallelism, f.apply))

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: japi.Predicate[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.filter(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.collect(pf))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): javadsl.Flow[In, java.util.List[Out @uncheckedVariance], Mat] =
    new Flow(delegate.grouped(n).map(_.asJava)) // FIXME optimize to one step

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   */
  def scan[T](zero: T)(f: japi.Function2[T, Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.scan(zero)(f.apply))

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
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Flow[In, java.util.List[Out @uncheckedVariance], Mat] =
    new Flow(delegate.groupedWithin(n, d).map(_.asJava)) // FIXME optimize to one step

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Long): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.dropWithin(d))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
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
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate[S](seed: japi.Function[Out, S], aggregate: japi.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
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
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: japi.Function[Out, S], extrapolate: japi.Function[S, akka.japi.Pair[U, S]]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.expand(seed(_))(s ⇒ {
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
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[U](mkStage: japi.Creator[Stage[Out, U]]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.transform(() ⇒ mkStage.create()))

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
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
   */
  def groupBy[K](f: japi.Function[Out, K]): javadsl.Flow[In, akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance, Unit]], Mat] =
    new Flow(delegate.groupBy(f.apply).map { case (k, p) ⇒ akka.japi.Pair(k, p.asJava) }) // FIXME optimize to one step

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
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
   * the element is dropped and the stream and substreams continue.
   */
  def splitWhen(p: japi.Predicate[Out]): javadsl.Flow[In, Source[Out, Unit], Mat] =
    new Flow(delegate.splitWhen(p.test).map(_.asJava))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.flatten(strategy))

  /**
   * Returns a new `Flow` that concatenates a secondary `Source` to this flow so that,
   * the first element emitted by the given ("second") source is emitted after the last element of this Flow.
   */
  def concat[M](second: javadsl.Source[Out @uncheckedVariance, M]): javadsl.Flow[In, Out, Mat @uncheckedVariance Pair M] =
    new Flow(delegate.concat(second.asScala).mapMaterialized(p ⇒ Pair(p._1, p._2)))

  def withAttributes(attr: OperationAttributes): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.withAttributes(attr))

  def named(name: String): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.named(name))
}

/**
 * Java API
 *
 * Flow with attached input and output, can be executed.
 */
trait RunnableFlow[+Mat] extends Graph[ClosedShape, Mat] {
  /**
   * Run this flow and return the materialized values of the flow.
   */
  def run(materializer: FlowMaterializer): Mat
  /**
   * Transform only the materialized value of this RunnableFlow, leaving all other properties as they were.
   */
  def mapMaterialized[Mat2](f: japi.Function[Mat, Mat2]): RunnableFlow[Mat2]
}

/** INTERNAL API */
private[akka] class RunnableFlowAdapter[Mat](runnable: scaladsl.RunnableFlow[Mat]) extends RunnableFlow[Mat] {
  def shape = ClosedShape
  def module = runnable.module
  override def mapMaterialized[Mat2](f: japi.Function[Mat, Mat2]): RunnableFlow[Mat2] =
    new RunnableFlowAdapter(runnable.mapMaterialized(f.apply _))
  override def run(materializer: FlowMaterializer): Mat = runnable.run()(materializer)
}
