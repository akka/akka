/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.javadsl
import akka.stream.scaladsl
import akka.stream._
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import scala.concurrent.Future
import akka.stream.impl.StreamLayout

/** Java API */
object Sink {

  import akka.stream.scaladsl.JavaConverters._

  val factory: SinkCreate = new SinkCreate {}

  /** Adapt [[scaladsl.Sink]] for use within Java DSL */
  def adapt[O, M](sink: scaladsl.Sink[O, M]): javadsl.Sink[O, M] =
    new Sink(sink)

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U, In](zero: U, f: japi.Function2[U, In, U]): javadsl.Sink[In, Future[U]] =
    new Sink(scaladsl.Sink.fold[U, In](zero)(f.apply))

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def create[In](subs: Subscriber[In]): Sink[In, Unit] =
    new Sink(scaladsl.Sink(subs))

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorSubscriber]].
   */
  def create[T](props: Props): Sink[T, ActorRef] =
    new Sink(scaladsl.Sink.apply(props))

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Sink[T, Unit] =
    new Sink(scaladsl.Sink.cancelled)

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore[T](): Sink[T, Unit] =
    new Sink(scaladsl.Sink.ignore)

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   * that can handle one [[org.reactivestreams.Subscriber]].
   */
  def publisher[In](): Sink[In, Publisher[In]] =
    new Sink(scaladsl.Sink.publisher())

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream..
   */
  def foreach[T](f: japi.Procedure[T]): Sink[T, Future[Unit]] =
    new Sink(scaladsl.Sink.foreach(f.apply))

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]]
   * that can handle more than one [[org.reactivestreams.Subscriber]].
   */
  def fanoutPublisher[T](initialBufferSize: Int, maximumBufferSize: Int): Sink[T, Publisher[T]] =
    new Sink(scaladsl.Sink.fanoutPublisher(initialBufferSize, maximumBufferSize))

  /**
   * A `Sink` that when the flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[In](onComplete: japi.Procedure[Unit]): Sink[In, Unit] =
    new Sink(scaladsl.Sink.onComplete[In](x ⇒ onComplete.apply(x)))

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   */
  def head[In]: Sink[In, Future[In]] =
    new Sink(scaladsl.Sink.head[In])

}

/**
 * Java API
 *
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
class Sink[-In, +Mat](delegate: scaladsl.Sink[In, Mat]) extends Graph[SinkShape[In], Mat] {

  override def shape: SinkShape[In] = delegate.shape
  private[stream] def module: StreamLayout.Module = delegate.module

  /** Converts this Sink to it's Scala DSL counterpart */
  def asScala: scaladsl.Sink[In, Mat] = delegate

  /**
   * Connect this `Sink` to a `Source` and run it.
   */
  // TODO shouldn’t this return M?
  def runWith[M](source: javadsl.Source[In, M], materializer: FlowMaterializer): M =
    asScala.runWith(source.asScala)(materializer)

  /**
   * Transform only the materialized value of this Sink, leaving all other properties as they were.
   */
  def mapMaterialized[Mat2](f: japi.Function[Mat, Mat2]): Sink[In, Mat2] =
    new Sink(delegate.mapMaterialized(f.apply _))
}
