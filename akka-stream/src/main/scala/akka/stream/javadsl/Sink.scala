/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util.Optional

import akka.{ Done, NotUsed, japi }
import akka.actor.{ ActorRef, Props }
import akka.dispatch.ExecutionContexts
import akka.japi.function
import akka.stream.impl.{ LinearTraversalBuilder, SinkQueueAdapter }
import akka.stream.{ javadsl, scaladsl, _ }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.compat.java8.OptionConverters._
import scala.concurrent.ExecutionContext
import scala.util.Try
import java.util.concurrent.CompletionStage
import scala.collection.immutable
import scala.annotation.unchecked.uncheckedVariance
import scala.compat.java8.FutureConverters._

/** Java API */
object Sink {
  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   */
  def fold[U, In](zero: U, f: function.Function2[U, In, U]): javadsl.Sink[In, CompletionStage[U]] =
    new Sink(scaladsl.Sink.fold[U, In](zero)(f.apply).toCompletionStage())

  /**
   * A `Sink` that will invoke the given asynchronous function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   */
  def foldAsync[U, In](zero: U, f: function.Function2[U, In, CompletionStage[U]]): javadsl.Sink[In, CompletionStage[U]] = new Sink(scaladsl.Sink.foldAsync[U, In](zero)(f(_, _).toScala).toCompletionStage())

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (from the second element) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   */
  def reduce[In](f: function.Function2[In, In, In]): Sink[In, CompletionStage[In]] =
    new Sink(scaladsl.Sink.reduce[In](f.apply).toCompletionStage())

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def fromSubscriber[In](subs: Subscriber[In]): Sink[In, NotUsed] =
    new Sink(scaladsl.Sink.fromSubscriber(subs))

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T](): Sink[T, NotUsed] =
    new Sink(scaladsl.Sink.cancelled)

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore[T](): Sink[T, CompletionStage[Done]] =
    new Sink(scaladsl.Sink.ignore.toCompletionStage())

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   *
   * If `fanout` is `true`, the materialized `Publisher` will support multiple `Subscriber`s and
   * the size of the `inputBuffer` configured for this operator becomes the maximum number of elements that
   * the fastest [[org.reactivestreams.Subscriber]] can be ahead of the slowest one before slowing
   * the processing down due to back pressure.
   *
   * If `fanout` is `false` then the materialized `Publisher` will only support a single `Subscriber` and
   * reject any additional `Subscriber`s.
   */
  def asPublisher[T](fanout: AsPublisher): Sink[T, Publisher[T]] =
    new Sink(scaladsl.Sink.asPublisher(fanout == AsPublisher.WITH_FANOUT))

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[java.util.concurrent.CompletionStage]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreach[T](f: function.Procedure[T]): Sink[T, CompletionStage[Done]] =
    new Sink(scaladsl.Sink.foreach(f.apply).toCompletionStage())

  /**
   * A `Sink` that will invoke the given procedure asynchronously for each received element. The sink is materialized
   * into a [[java.util.concurrent.CompletionStage]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreachAsync[T](parallelism: Int)(f: function.Function[T, CompletionStage[Void]]): Sink[T, CompletionStage[Done]] =
    new Sink(scaladsl.Sink.foreachAsync(parallelism)((x: T) ⇒ f(x).toScala.map(_ ⇒ ())(ExecutionContexts.sameThreadExecutionContext)).toCompletionStage())

  /**
   * A `Sink` that will invoke the given procedure for each received element in parallel. The sink is materialized
   * into a [[java.util.concurrent.CompletionStage]].
   *
   * If `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Stop]] the `CompletionStage` will be completed with failure.
   *
   * If `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]] the
   * element is dropped and the stream continues.
   */
  @deprecated("Use `foreachAsync` instead, it allows you to choose how to run the procedure, by calling some other API returning a CompletionStage or using CompletableFuture.supplyAsync.", since = "2.5.17")
  def foreachParallel[T](parallel: Int)(f: function.Procedure[T])(ec: ExecutionContext): Sink[T, CompletionStage[Done]] =
    new Sink(scaladsl.Sink.foreachParallel(parallel)(f.apply)(ec).toCompletionStage())

  /**
   * A `Sink` that when the flow is completed, either through a failure or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[In](callback: function.Procedure[Try[Done]]): Sink[In, NotUsed] =
    new Sink(scaladsl.Sink.onComplete[In](x ⇒ callback.apply(x)))

  /**
   * A `Sink` that materializes into a `CompletionStage` of the first value received.
   * If the stream completes before signaling at least a single element, the CompletionStage will be failed with a [[NoSuchElementException]].
   * If the stream signals an error before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[headOption]].
   */
  def head[In](): Sink[In, CompletionStage[In]] =
    new Sink(scaladsl.Sink.head[In].toCompletionStage())

  /**
   * A `Sink` that materializes into a `CompletionStage` of the optional first value received.
   * If the stream completes before signaling at least a single element, the value of the CompletionStage will be an empty [[java.util.Optional]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[head]].
   */
  def headOption[In](): Sink[In, CompletionStage[Optional[In]]] =
    new Sink(scaladsl.Sink.headOption[In].mapMaterializedValue(
      _.map(_.asJava)(ExecutionContexts.sameThreadExecutionContext).toJava))

  /**
   * A `Sink` that materializes into a `CompletionStage` of the last value received.
   * If the stream completes before signaling at least a single element, the CompletionStage will be failed with a [[NoSuchElementException]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[lastOption]], [[takeLast]].
   */
  def last[In](): Sink[In, CompletionStage[In]] =
    new Sink(scaladsl.Sink.last[In].toCompletionStage())

  /**
   * A `Sink` that materializes into a `CompletionStage` of the optional last value received.
   * If the stream completes before signaling at least a single element, the value of the CompletionStage will be an empty [[java.util.Optional]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[head]], [[takeLast]].
   */
  def lastOption[In](): Sink[In, CompletionStage[Optional[In]]] =
    new Sink(scaladsl.Sink.lastOption[In].mapMaterializedValue(
      _.map(_.asJava)(ExecutionContexts.sameThreadExecutionContext).toJava))

  /**
   * A `Sink` that materializes into a a `CompletionStage` of `List<In>` containing the last `n` collected elements.
   *
   * If the stream completes before signaling at least n elements, the `CompletionStage` will complete with all elements seen so far.
   * If the stream never completes the `CompletionStage` will never complete.
   * If there is a failure signaled in the stream the `CompletionStage` will be completed with failure.
   */
  def takeLast[In](n: Int): Sink[In, CompletionStage[java.util.List[In]]] = {
    import scala.collection.JavaConverters._
    new Sink(scaladsl.Sink.takeLast[In](n).mapMaterializedValue(fut ⇒ fut.map(sq ⇒ sq.asJava)(ExecutionContexts.sameThreadExecutionContext).toJava))
  }

  /**
   * A `Sink` that keeps on collecting incoming elements until upstream terminates.
   * As upstream may be unbounded, `Flow[T].take` or the stricter `Flow[T].limit` (and their variants)
   * may be used to ensure boundedness.
   * Materializes into a `CompletionStage` of `Seq[T]` containing all the collected elements.
   * `List` is limited to `Integer.MAX_VALUE` elements, this Sink will cancel the stream
   * after having received that many elements.
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]], [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def seq[In]: Sink[In, CompletionStage[java.util.List[In]]] = {
    import scala.collection.JavaConverters._
    new Sink(scaladsl.Sink.seq[In].mapMaterializedValue(fut ⇒ fut.map(sq ⇒ sq.asJava)(ExecutionContexts.sameThreadExecutionContext).toJava))
  }

  /**
   * Sends the elements of the stream to the given `ActorRef`.
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure a [[akka.actor.Status.Failure]]
   * message will be sent to the destination actor.
   *
   * It will request at most `maxInputBufferSize` number of elements from
   * upstream, but there is no back-pressure signal from the destination actor,
   * i.e. if the actor is not consuming the messages fast enough the mailbox
   * of the actor will grow. For potentially slow consumer actors it is recommended
   * to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
   * limiting operator in front of this `Sink`.
   *
   */
  def actorRef[In](ref: ActorRef, onCompleteMessage: Any): Sink[In, NotUsed] =
    new Sink(scaladsl.Sink.actorRef[In](ref, onCompleteMessage))

  /**
   * Sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
   * First element is always `onInitMessage`, then stream is waiting for acknowledgement message
   * `ackMessage` from the given actor which means that it is ready to process
   * elements. It also requires `ackMessage` message after each stream element
   * to make backpressure work.
   *
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure - result of `onFailureMessage(throwable)`
   * message will be sent to the destination actor.
   */
  def actorRefWithAck[In](ref: ActorRef, onInitMessage: Any, ackMessage: Any, onCompleteMessage: Any,
                          onFailureMessage: function.Function[Throwable, Any]): Sink[In, NotUsed] =
    new Sink(scaladsl.Sink.actorRefWithAck[In](ref, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage.apply _))

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorSubscriber]].
   *
   * @deprecated Use `akka.stream.stage.GraphStage` and `fromGraph` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.
   */
  @deprecated("Use `akka.stream.stage.GraphStage` and `fromGraph` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.", since = "2.5.0")
  def actorSubscriber[T](props: Props): Sink[T, ActorRef] =
    new Sink(scaladsl.Sink.actorSubscriber(props))

  /**
   * A graph with the shape of a sink logically is a sink, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SinkShape[T], M]): Sink[T, M] =
    g match {
      case s: Sink[T, M] ⇒ s
      case other         ⇒ new Sink(scaladsl.Sink.fromGraph(other))
    }

  /**
   * Combine several sinks with fan-out strategy like `Broadcast` or `Balance` and returns `Sink`.
   */
  def combine[T, U](output1: Sink[U, _], output2: Sink[U, _], rest: java.util.List[Sink[U, _]], strategy: function.Function[java.lang.Integer, Graph[UniformFanOutShape[T, U], NotUsed]]): Sink[T, NotUsed] = {
    import scala.collection.JavaConverters._
    val seq = if (rest != null) rest.asScala.map(_.asScala).toSeq else immutable.Seq()
    new Sink(scaladsl.Sink.combine(output1.asScala, output2.asScala, seq: _*)(num ⇒ strategy.apply(num)))
  }

  /**
   * Creates a `Sink` that is materialized as an [[akka.stream.javadsl.SinkQueue]].
   * [[akka.stream.javadsl.SinkQueue.pull]] method is pulling element from the stream and returns ``CompletionStage[Option[T]]``.
   * `CompletionStage` completes when element is available.
   *
   * Before calling pull method second time you need to wait until previous CompletionStage completes.
   * Pull returns Failed future with ''IllegalStateException'' if previous future has not yet completed.
   *
   * `Sink` will request at most number of elements equal to size of `inputBuffer` from
   * upstream and then stop back pressure.  You can configure size of input
   * buffer by using [[Sink.withAttributes]] method.
   *
   * For stream completion you need to pull all elements from [[akka.stream.javadsl.SinkQueue]] including last None
   * as completion marker
   *
   * @see [[akka.stream.javadsl.SinkQueueWithCancel]]
   */
  def queue[T](): Sink[T, SinkQueueWithCancel[T]] =
    new Sink(scaladsl.Sink.queue[T]().mapMaterializedValue(new SinkQueueAdapter(_)))

  /**
   * Creates a real `Sink` upon receiving the first element. Internal `Sink` will not be created if there are no elements,
   * because of completion or error.
   *
   * If upstream completes before an element was received then the `Future` is completed with the value created by fallback.
   * If upstream fails before an element was received, `sinkFactory` throws an exception, or materialization of the internal
   * sink fails then the `Future` is completed with the exception.
   * Otherwise the `Future` is completed with the materialized value of the internal sink.
   */
  @Deprecated
  @deprecated("Use lazyInitAsync instead. (lazyInitAsync no more needs a fallback function and the materialized value more clearly indicates if the internal sink was materialized or not.)", "2.5.11")
  def lazyInit[T, M](sinkFactory: function.Function[T, CompletionStage[Sink[T, M]]], fallback: function.Creator[M]): Sink[T, CompletionStage[M]] =
    new Sink(scaladsl.Sink.lazyInit[T, M](
      t ⇒ sinkFactory.apply(t).toScala.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext),
      () ⇒ fallback.create()).mapMaterializedValue(_.toJava))

  /**
   * Creates a real `Sink` upon receiving the first element. Internal `Sink` will not be created if there are no elements,
   * because of completion or error.
   *
   * If upstream completes before an element was received then the `Future` is completed with `None`.
   * If upstream fails before an element was received, `sinkFactory` throws an exception, or materialization of the internal
   * sink fails then the `Future` is completed with the exception.
   * Otherwise the `Future` is completed with the materialized value of the internal sink.
   */
  def lazyInitAsync[T, M](sinkFactory: function.Creator[CompletionStage[Sink[T, M]]]): Sink[T, CompletionStage[Optional[M]]] = {
    val sSink = scaladsl.Sink.lazyInitAsync[T, M](
      () ⇒ sinkFactory.create().toScala.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext)
    ).mapMaterializedValue(fut ⇒ fut.map(_.fold(Optional.empty[M]())(m ⇒ Optional.ofNullable(m)))(ExecutionContexts.sameThreadExecutionContext).toJava)
    new Sink(sSink)
  }
}

/**
 * Java API
 *
 * A `Sink` is a set of stream processing steps that has one open input.
 * Can be used as a `Subscriber`
 */
final class Sink[In, Mat](delegate: scaladsl.Sink[In, Mat]) extends Graph[SinkShape[In], Mat] {

  override def shape: SinkShape[In] = delegate.shape
  override def traversalBuilder: LinearTraversalBuilder = delegate.traversalBuilder

  override def toString: String = delegate.toString

  /**
   * Converts this Sink to its Scala DSL counterpart.
   */
  def asScala: scaladsl.Sink[In, Mat] = delegate

  /**
   * Connect this `Sink` to a `Source` and run it.
   */
  def runWith[M](source: Graph[SourceShape[In], M], materializer: Materializer): M =
    asScala.runWith(source)(materializer)

  /**
   * Transform this Sink by applying a function to each *incoming* upstream element before
   * it is passed to the [[Sink]]
   *
   * '''Backpressures when''' original [[Sink]] backpressures
   *
   * '''Cancels when''' original [[Sink]] backpressures
   */
  def contramap[In2](f: function.Function[In2, In]): Sink[In2, Mat] =
    javadsl.Flow.fromFunction(f).toMat(this, Keep.right[NotUsed, Mat])

  /**
   * Transform only the materialized value of this Sink, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): Sink[In, Mat2] =
    new Sink(delegate.mapMaterializedValue(f.apply _))

  /**
   * Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink
   * that can be consume elements 'into' the pre-materialized one.
   *
   * Useful for when you need a materialized value of a Sink when handing it out to someone to materialize it for you.
   */
  def preMaterialize(materializer: Materializer): japi.Pair[Mat @uncheckedVariance, Sink[In @uncheckedVariance, NotUsed]] = {
    val (mat, sink) = delegate.preMaterialize()(materializer)
    akka.japi.Pair(mat, sink.asJava)
  }

  /**
   * Replace the attributes of this [[Sink]] with the given ones. If this Sink is a composite
   * of multiple graphs, new attributes on the composite will be less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def withAttributes(attr: Attributes): javadsl.Sink[In, Mat] =
    new Sink(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this [[Sink]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this Sink is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): javadsl.Sink[In, Mat] =
    new Sink(delegate.addAttributes(attr))

  /**
   * Add a ``name`` attribute to this Sink.
   */
  override def named(name: String): javadsl.Sink[In, Mat] =
    new Sink(delegate.named(name))

  /**
   * Put an asynchronous boundary around this `Sink`
   */
  override def async: javadsl.Sink[In, Mat] =
    new Sink(delegate.async)

  /**
   * Put an asynchronous boundary around this `Sink`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): javadsl.Sink[In, Mat] =
    new Sink(delegate.async(dispatcher))

  /**
   * Put an asynchronous boundary around this `Sink`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): javadsl.Sink[In, Mat] =
    new Sink(delegate.async(dispatcher, inputBufferSize))

}
