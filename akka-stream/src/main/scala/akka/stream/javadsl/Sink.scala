/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.javadsl
import akka.stream.scaladsl
import akka.stream.FlowMaterializer
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import scala.concurrent.Future

/** Java API */
object Sink {

  import akka.stream.scaladsl.JavaConverters._

  /** Adapt [[scaladsl.Sink]] for use within Java DSL */
  def adapt[O](sink: scaladsl.Sink[O]): javadsl.Sink[O] =
    new Sink(sink)

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   */
  def fold[U, In](zero: U, f: japi.Function2[U, In, U]): javadsl.KeyedSink[In, Future[U]] =
    new KeyedSink(scaladsl.Sink.fold[U, In](zero)(f.apply))

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def create[In](subs: Subscriber[In]): Sink[In] =
    new Sink[In](scaladsl.Sink(subs))

  /**
   * Creates a `Sink` by using an empty [[FlowGraphBuilder]] on a block that expects a [[FlowGraphBuilder]] and
   * returns the `UndefinedSource`.
   */
  def create[T]()(block: japi.Function[FlowGraphBuilder, UndefinedSource[T]]): Sink[T] =
    new Sink(scaladsl.Sink.apply() { b ⇒ block.apply(b.asJava).asScala })

  /**
   * Creates a `Sink` by using a FlowGraphBuilder from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSource`.
   */
  def create[T](graph: PartialFlowGraph, block: japi.Function[FlowGraphBuilder, UndefinedSource[T]]): Sink[T] =
    new Sink[T](scaladsl.Sink.apply(graph.asScala) { b ⇒ block.apply(b.asJava).asScala })

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorSubscriber]].
   */
  def create[T](props: Props): KeyedSink[T, ActorRef] =
    new KeyedSink(scaladsl.Sink.apply(props))

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Sink[T] =
    new Sink(scaladsl.Sink.cancelled)

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore[T](): Sink[T] =
    new Sink(scaladsl.Sink.ignore)

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   * that can handle one [[org.reactivestreams.Subscriber]].
   */
  def publisher[In](): KeyedSink[In, Publisher[In]] =
    new KeyedSink(scaladsl.Sink.publisher)

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure is signaled in
   * the stream..
   */
  def foreach[T](f: japi.Procedure[T]): KeyedSink[T, Future[Unit]] =
    new KeyedSink(scaladsl.Sink.foreach(f.apply))

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]]
   * that can handle more than one [[org.reactivestreams.Subscriber]].
   */
  def fanoutPublisher[T](initialBufferSize: Int, maximumBufferSize: Int): KeyedSink[T, Publisher[T]] =
    new KeyedSink(scaladsl.Sink.fanoutPublisher(initialBufferSize, maximumBufferSize))

  /**
   * A `Sink` that when the flow is completed, either through a failure or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[In](onComplete: japi.Procedure[Unit]): Sink[In] =
    new Sink(scaladsl.Sink.onComplete[In](x ⇒ onComplete.apply(x)))

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   */
  def head[In]: KeyedSink[In, Future[In]] =
    new KeyedSink(scaladsl.Sink.head[In])

}

/**
 * Java API
 *
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
class Sink[-In](delegate: scaladsl.Sink[In]) {

  /** Converts this Sink to it's Scala DSL counterpart */
  def asScala: scaladsl.Sink[In] = delegate

  // RUN WITH //

  /**
   * Connect the `KeyedSource` to this `Sink` and run it.
   *
   * The returned value is the materialized value of the `KeyedSource`, e.g. the `Subscriber` of a `Source.subscriber()`.
   *
   * @tparam T materialized type of given Source
   */
  def runWith[T](source: javadsl.KeyedSource[In, T], materializer: FlowMaterializer): T =
    asScala.runWith(source.asScala)(materializer).asInstanceOf[T]

  /**
   * Connect this `Sink` to a `Source` and run it.
   */
  def runWith(source: javadsl.Source[In], materializer: FlowMaterializer): Unit =
    asScala.runWith(source.asScala)(materializer)
}

/**
 * Java API
 *
 * A `Sink` that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this sink (could be a completion Future
 * or a cancellation handle, etc.)
 */
final class KeyedSink[-In, M](delegate: scaladsl.KeyedSink[In, M]) extends javadsl.Sink[In](delegate) with KeyedMaterializable[M] {
  override def asScala: scaladsl.KeyedSink[In, M] = super.asScala.asInstanceOf[scaladsl.KeyedSink[In, M]]
}
