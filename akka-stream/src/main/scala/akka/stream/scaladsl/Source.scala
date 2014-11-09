/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.language.higherKinds

import akka.actor.Props
import akka.stream.impl.{ EmptyPublisher, ErrorPublisher, SynchronousPublisherFromIterable }
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import akka.stream.FlowMaterializer

/**
 * A `Source` is a set of stream processing steps that has one open output and an attached input.
 * Can be used as a `Publisher`
 */
trait Source[+Out] extends FlowOps[Out] {
  type MaterializedType
  override type Repr[+O] <: Source[O]

  /**
   * Transform this [[akka.stream.scaladsl.Source]] by appending the given processing stages.
   */
  def via[T](flow: Flow[Out, T]): Source[T]

  /**
   * Connect this [[akka.stream.scaladsl.Source]] to a [[akka.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def to(sink: Sink[Out]): RunnableFlow

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a [[akka.stream.scaladsl.Sink#publisher]].
   */
  def runWith(sink: Sink[Out])(implicit materializer: FlowMaterializer): sink.MaterializedType = to(sink).run().get(sink)

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U](zero: U)(f: (U, Out) ⇒ U)(implicit materializer: FlowMaterializer): Future[U] = runWith(FoldSink(zero)(f)) // FIXME why is fold always an end step?

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream.
   */
  def foreach(f: Out ⇒ Unit)(implicit materializer: FlowMaterializer): Future[Unit] = runWith(ForeachSink(f))

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   */
  def concat[Out2 >: Out](second: Source[Out2]): Source[Out2] = Source.concat(this, second)

  /**
   * Concatenates a second source so that the first element
   * emitted by that source is emitted after the last element of this
   * source.
   *
   * This is a shorthand for [[concat]]
   */
  def ++[Out2 >: Out](second: Source[Out2]): Source[Out2] = concat(second)

}

object Source {
  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](publisher: Publisher[T]): Source[T] = PublisherSource(publisher)

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source(() => Iterator.from(0))`
   *
   * Start a new `Source` from the given function that produces anIterator.
   * The produced stream of elements will continue until the iterator runs empty
   * or fails during evaluation of the `next()` method.
   * Elements are pulled out of the iterator in accordance with the demand coming
   * from the downstream transformation steps.
   */
  def apply[T](f: () ⇒ Iterator[T]): Source[T] = apply(new FuncIterable(f))

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage: `Source(Seq(1,2,3))`
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Source[T] = IterableSource(iterable)

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): Source[T] = FutureSource(future)

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ T): Source[T] = // FIXME why is tick () => T and not T?
    TickSource(initialDelay, interval, tick)

  /**
   * Creates a `Source` by using an empty [[FlowGraphBuilder]] on a block that expects a [[FlowGraphBuilder]] and
   * returns the `UndefinedSink`.
   */
  def apply[T]()(block: FlowGraphBuilder ⇒ UndefinedSink[T]): Source[T] =
    createSourceFromBuilder(new FlowGraphBuilder(), block)

  /**
   * Creates a `Source` by using a [[FlowGraphBuilder]] from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSink`.
   */
  def apply[T](graph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ UndefinedSink[T]): Source[T] =
    createSourceFromBuilder(new FlowGraphBuilder(graph.graph), block)

  private def createSourceFromBuilder[T](builder: FlowGraphBuilder, block: FlowGraphBuilder ⇒ UndefinedSink[T]): Source[T] = {
    val out = block(builder)
    builder.partialBuild().toSource(out)
  }

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorPublisher]].
   */
  def apply[T](props: Props): PropsSource[T] = PropsSource(props)

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def singleton[T](element: T): Source[T] = apply(SynchronousPublisherFromIterable(List(element))) // FIXME optimize

  /**
   * A `Source` with no elements, i.e. an empty stream that is completed immediately for every connected `Sink`.
   */
  def empty[T](): Source[T] = _empty
  private[this] val _empty: Source[Nothing] = apply(EmptyPublisher)

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T] = apply(ErrorPublisher(cause))

  /**
   * Concatenates two sources so that the first element
   * emitted by the second source is emitted after the last element of the first
   * source.
   */
  def concat[T](source1: Source[T], source2: Source[T]): Source[T] = ConcatSource(source1, source2)

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def subscriber[T]: SubscriberSource[T] = SubscriberSource[T]
}

/**
 * A `Source` that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this source (could be a Subscriber, a
 * Future/Promise, etc.).
 */
trait KeyedSource[+Out] extends Source[Out]
