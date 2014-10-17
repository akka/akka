/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl2
import org.reactivestreams.{ Publisher, Subscriber }

import scala.concurrent.Future

object Sink {
  /**
   * Java API
   *
   * Adapt [[scaladsl2.Sink]] for use within JavaDSL
   */
  def adapt[O](sink: scaladsl2.Sink[O]): javadsl.Sink[O] = SinkAdapter(sink)

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is an error is signaled in the stream.
   */
  def fold[U, In](zero: U, f: japi.Function2[U, In, U]): javadsl.KeyedSink[In, Future[U]] = KeyedSink(scaladsl2.Sink.fold[U, In](zero)(f.apply))

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is an error is signaled in
   * the stream..
   */
  def foreach[In](f: japi.Procedure[In]): javadsl.KeyedSink[In, Future[Unit]] = KeyedSink(scaladsl2.Sink.foreach[In](x ⇒ f(x)))

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def subscriber[In](subs: Subscriber[In]): KeyedSink[In, Subscriber[In]] = KeyedSink(scaladsl2.Sink(subs))

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   * that can handle one [[org.reactivestreams.Subscriber]].
   */
  def publisher[In](): KeyedSink[In, Publisher[In]] = KeyedSink(scaladsl2.Sink.publisher)

  /**
   * A `Sink` that when the flow is completed, either through an error or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[In](onComplete: akka.dispatch.OnComplete[Unit]): SimpleSink[In] =
    SimpleSink(scaladsl2.Sink.onComplete[In](x ⇒ onComplete.apply(x)))

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   */
  def future[In]: KeyedSink[In, Future[In]] = KeyedSink(scaladsl2.Sink.future[In])
}

/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
abstract class Sink[-In] extends javadsl.SinkOps[In]

/** INTERNAL API */
private[akka] object SinkAdapter {
  def apply[In](sink: scaladsl2.Sink[In]) = new SinkAdapter[In] { def delegate = sink }
}

/** INTERNAL API */
private[akka] abstract class SinkAdapter[-In] extends Sink[In] {

  protected def delegate: scaladsl2.Sink[In]

  /** Converts this Sink to it's Scala DSL counterpart */
  def asScala: scaladsl2.Sink[In] = delegate

  // RUN WITH //

  def runWith[T](source: javadsl.KeyedSource[In, T], materializer: scaladsl2.FlowMaterializer): T =
    delegate.runWith(source.asScala)(materializer).asInstanceOf[T]

  def runWith(source: javadsl.SimpleSource[In], materializer: scaladsl2.FlowMaterializer): Unit =
    delegate.runWith(source.asScala)(materializer)

}
