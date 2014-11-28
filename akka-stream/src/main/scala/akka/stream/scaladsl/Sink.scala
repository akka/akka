/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Props
import org.reactivestreams.Subscriber
import scala.util.Try
import akka.stream.FlowMaterializer

/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
trait Sink[-In] {
  type MaterializedType

  /**
   * Connect this `Sink` to a `Source` and run it. The returned value is the materialized value
   * of the `Source`, e.g. the `Subscriber` of a [[SubscriberSource]].
   */
  def runWith(source: Source[In])(implicit materializer: FlowMaterializer): source.MaterializedType =
    source.to(this).run().get(source)

}

object Sink {
  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def apply[T](subscriber: Subscriber[T]): Sink[T] = SubscriberSink(subscriber)

  /**
   * Creates a `Sink` by using an empty [[FlowGraphBuilder]] on a block that expects a [[FlowGraphBuilder]] and
   * returns the `UndefinedSource`.
   */
  def apply[T]()(block: FlowGraphBuilder ⇒ UndefinedSource[T]): Sink[T] =
    createSinkFromBuilder(new FlowGraphBuilder(), block)

  /**
   * Creates a `Sink` by using a FlowGraphBuilder from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSource`.
   */
  def apply[T](graph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ UndefinedSource[T]): Sink[T] =
    createSinkFromBuilder(new FlowGraphBuilder(graph), block)

  private def createSinkFromBuilder[T](builder: FlowGraphBuilder, block: FlowGraphBuilder ⇒ UndefinedSource[T]): Sink[T] = {
    val in = block(builder)
    builder.partialBuild().toSink(in)
  }

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorSubscriber]].
   */
  def apply[T](props: Props): PropsSink[T] = PropsSink[T](props)

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Sink[T] = CancelSink

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   */
  def head[T]: HeadSink[T] = HeadSink[T]

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   * that can handle one [[org.reactivestreams.Subscriber]].
   */
  def publisher[T]: PublisherSink[T] = PublisherSink[T]

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]]
   * that can handle more than one [[org.reactivestreams.Subscriber]].
   */
  def fanoutPublisher[T](initialBufferSize: Int, maximumBufferSize: Int): FanoutPublisherSink[T] =
    FanoutPublisherSink[T](initialBufferSize, maximumBufferSize)

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore: Sink[Any] = BlackholeSink

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream..
   */
  def foreach[T](f: T ⇒ Unit): ForeachSink[T] = ForeachSink(f)

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U, T](zero: U)(f: (U, T) ⇒ U): FoldSink[U, T] = FoldSink(zero)(f)

  /**
   * A `Sink` that when the flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[T](callback: Try[Unit] ⇒ Unit): Sink[T] = OnCompleteSink[T](callback)
}

/**
 * A `Sink` that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this sink (could be a completion Future
 * or a cancellation handle, etc.)
 */
trait KeyedSink[-In] extends Sink[In]
