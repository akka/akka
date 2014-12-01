/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._
import akka.japi.Util
import akka.stream.scaladsl
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.stream.stage.Stage

object Flow {

  import akka.stream.scaladsl.JavaConverters._

  /** Adapt [[scaladsl.Flow]] for use within Java DSL */
  def adapt[I, O](flow: scaladsl.Flow[I, O]): javadsl.Flow[I, O] =
    new Flow(flow)

  /** Create a `Flow` which can process elements of type `T`. */
  def empty[T](): javadsl.Flow[T, T] =
    Flow.create()

  /** Create a `Flow` which can process elements of type `T`. */
  def create[T](): javadsl.Flow[T, T] =
    Flow.adapt[T, T](scaladsl.Pipe.empty[T])

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](clazz: Class[T]): javadsl.Flow[T, T] =
    create[T]()

  /**
   * Creates a `Flow` by using an empty [[FlowGraphBuilder]] on a block that expects a [[FlowGraphBuilder]] and
   * returns the `UndefinedSource` and `UndefinedSink`.
   */
  def create[I, O](block: japi.Function[FlowGraphBuilder, akka.japi.Pair[UndefinedSource[I], UndefinedSink[O]]]): Flow[I, O] = {
    val sFlow = scaladsl.Flow() { b ⇒
      val pair = block.apply(b.asJava)
      pair.first.asScala → pair.second.asScala
    }
    new javadsl.Flow[I, O](sFlow)
  }

  /**
   * Creates a `Flow` by using a [[FlowGraphBuilder]] from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSource` and `UndefinedSink`.
   */
  def create[I, O](graph: PartialFlowGraph, block: japi.Function[javadsl.FlowGraphBuilder, akka.japi.Pair[UndefinedSource[I], UndefinedSink[O]]]): Flow[I, O] = {
    val sFlow = scaladsl.Flow(graph.asScala) { b ⇒
      val pair = block.apply(b.asJava)
      pair.first.asScala → pair.second.asScala
    }
    new Flow[I, O](sFlow)
  }

  /**
   * Create a flow from a seemingly disconnected Source and Sink pair.
   */
  def create[I, O](sink: javadsl.Sink[I], source: javadsl.Source[O]): Flow[I, O] =
    new Flow(scaladsl.Flow(sink.asScala, source.asScala))

}

/** Create a `Flow` which can process elements of type `T`. */
class Flow[-In, +Out](delegate: scaladsl.Flow[In, Out]) {
  import scala.collection.JavaConverters._
  import akka.stream.scaladsl.JavaConverters._

  /** Converts this Flow to it's Scala DSL counterpart */
  def asScala: scaladsl.Flow[In, Out] = delegate

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   */
  def via[T](flow: javadsl.Flow[Out, T]): javadsl.Flow[In, T] =
    new Flow(delegate.via(flow.asScala))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to(sink: javadsl.Sink[Out]): javadsl.Sink[In] =
    new Sink(delegate.to(sink.asScala))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableFlow]]
   */
  def join(flow: javadsl.Flow[Out, In]): javadsl.RunnableFlow =
    new RunnableFlowAdapter(delegate.join(flow.asScala))

  /**
   * Connect the `KeyedSource` to this `Flow` and then connect it to the `KeyedSink` and run it.
   *
   * The returned tuple contains the materialized values of the `KeyedSource` and `KeyedSink`,
   * e.g. the `Subscriber` of a `Source.subscriber()` and `Publisher` of a `Sink.publisher()`.
   *
   * @tparam T materialized type of given KeyedSource
   * @tparam U materialized type of given KeyedSink
   */
  def runWith[T, U](source: javadsl.KeyedSource[In, T], sink: javadsl.KeyedSink[Out, U], materializer: FlowMaterializer): akka.japi.Pair[T, U] = {
    val p = delegate.runWith(source.asScala, sink.asScala)(materializer)
    akka.japi.Pair(p._1.asInstanceOf[T], p._2.asInstanceOf[U])
  }

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `KeyedSink` and run it.
   *
   * The returned value will contain the materialized value of the `KeyedSink`, e.g. `Publisher` of a `Sink.publisher()`.
   *
   * @tparam T materialized type of given KeyedSink
   */
  def runWith[T](source: javadsl.Source[In], sink: javadsl.KeyedSink[Out, T], materializer: FlowMaterializer): T =
    delegate.runWith(source.asScala, sink.asScala)(materializer).asInstanceOf[T]

  /**
   * Connect the `KeyedSource` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * The returned value will contain the materialized value of the `KeyedSource`, e.g. `Subscriber` of a `Source.from(publisher)`.
   *
   * @tparam T materialized type of given KeyedSource
   */
  def runWith[T](source: javadsl.KeyedSource[In, T], sink: javadsl.Sink[Out], materializer: FlowMaterializer): T =
    delegate.runWith(source.asScala, sink.asScala)(materializer).asInstanceOf[T]

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * As both `Source` and `Sink` are "simple", no value is returned from this `runWith` overload.
   */
  def runWith(source: javadsl.Source[In], sink: javadsl.Sink[Out], materializer: FlowMaterializer): Unit =
    delegate.runWith(source.asScala, sink.asScala)(materializer)

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: japi.Function[Out, T]): javadsl.Flow[In, T] =
    new Flow(delegate.map(f.apply))

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: japi.Function[Out, java.util.List[T]]): javadsl.Flow[In, T] =
    new Flow(delegate.mapConcat(elem ⇒ Util.immutableSeq(f.apply(elem))))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](f: japi.Function[Out, Future[T]]): javadsl.Flow[In, T] =
    new Flow(delegate.mapAsync(f.apply))

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
  def mapAsyncUnordered[T](f: japi.Function[Out, Future[T]]): javadsl.Flow[In, T] =
    new Flow(delegate.mapAsyncUnordered(f.apply))

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: japi.Predicate[Out]): javadsl.Flow[In, Out] =
    new Flow(delegate.filter(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Flow[In, T] =
    new Flow(delegate.collect(pf))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): javadsl.Flow[In, java.util.List[Out @uncheckedVariance]] =
    new Flow(delegate.grouped(n).map(_.asJava)) // FIXME optimize to one step

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   */
  def scan[T](zero: T)(f: japi.Function2[T, Out, T]): javadsl.Flow[In, T] =
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
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Flow[In, java.util.List[Out @uncheckedVariance]] =
    new Flow(delegate.groupedWithin(n, d).map(_.asJava)) // FIXME optimize to one step

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): javadsl.Flow[In, Out] =
    new Flow(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): javadsl.Flow[In, Out] =
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
  def take(n: Int): javadsl.Flow[In, Out] =
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
  def takeWithin(d: FiniteDuration): javadsl.Flow[In, Out] =
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
  def conflate[S](seed: japi.Function[Out, S], aggregate: japi.Function2[S, Out, S]): javadsl.Flow[In, S] =
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
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: japi.Function[Out, S], extrapolate: japi.Function[S, akka.japi.Pair[U, S]]): javadsl.Flow[In, U] =
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
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out] =
    new Flow(delegate.buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[U](mkStage: japi.Creator[Stage[Out, U]]): javadsl.Flow[In, U] =
    new Flow(delegate.transform(() ⇒ mkStage.create()))

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail(n: Int): javadsl.Flow[In, akka.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance]]] =
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
   */
  def groupBy[K](f: japi.Function[Out, K]): javadsl.Flow[In, akka.japi.Pair[K, javadsl.Source[Out @uncheckedVariance]]] =
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
   */
  def splitWhen(p: japi.Predicate[Out]): javadsl.Flow[In, Source[Out]] =
    new Flow(delegate.splitWhen(p.test).map(_.asJava))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): javadsl.Flow[In, U] =
    new Flow(delegate.flatten(strategy))

  /**
   * Returns a new `Flow` that concatenates a secondary `Source` to this flow so that,
   * the first element emitted by the given ("second") source is emitted after the last element of this Flow.
   */
  def concat(second: javadsl.Source[In]): javadsl.Flow[In, Out] =
    new Flow(delegate.concat(second.asScala))

  /**
   * Add a key that will have a value available after materialization.
   * The key can only use other keys if they have been added to the flow
   * before this key.
   */
  def withKey[T](key: javadsl.Key[T]): Flow[In, Out] =
    new Flow(delegate.withKey(key.asScala))

  /**
   * Applies given [[OperationAttributes]] to a given section.
   */
  def section[I <: In, O](attributes: OperationAttributes, section: japi.Function[javadsl.Flow[In, Out], javadsl.Flow[I, O]]): javadsl.Flow[I, O] =
    new Flow(delegate.section(attributes.asScala) {
      val scalaToJava = (flow: scaladsl.Flow[In, Out]) ⇒ new javadsl.Flow[In, Out](flow)
      val javaToScala = (flow: javadsl.Flow[I, O]) ⇒ flow.asScala
      scalaToJava andThen section.apply andThen javaToScala
    })
}

/**
 * Java API
 *
 * Flow with attached input and output, can be executed.
 */
trait RunnableFlow {
  def run(materializer: FlowMaterializer): javadsl.MaterializedMap
}

/** INTERNAL API */
private[akka] class RunnableFlowAdapter(runnable: scaladsl.RunnableFlow) extends RunnableFlow {
  override def run(materializer: FlowMaterializer): MaterializedMap =
    new MaterializedMap(runnable.run()(materializer))
}
