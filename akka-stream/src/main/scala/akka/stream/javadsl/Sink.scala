/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import java.util.Optional
import akka.{ Done, NotUsed }
import akka.actor.{ ActorRef, Props }
import akka.dispatch.ExecutionContexts
import akka.japi.function
import akka.stream.impl.StreamLayout
import akka.stream.{ javadsl, scaladsl, _ }
import org.reactivestreams.{ Publisher, Subscriber }
import scala.compat.java8.OptionConverters._
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Try
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters.FutureOps
import akka.stream.impl.SinkQueueAdapter

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
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (from the second element) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce stage will fail its downstream with a [[NoSuchElementException]],
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
   * the size of the `inputBuffer` configured for this stage becomes the maximum number of elements that
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
   * into a [[java.util.concurrent.CompletionStage]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure is signaled in
   * the stream..
   */
  def foreach[T](f: function.Procedure[T]): Sink[T, CompletionStage[Done]] =
    new Sink(scaladsl.Sink.foreach(f.apply).toCompletionStage())

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
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
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
   * See also [[lastOption]].
   */
  def last[In](): Sink[In, CompletionStage[In]] =
    new Sink(scaladsl.Sink.last[In].toCompletionStage())

  /**
   * A `Sink` that materializes into a `CompletionStage` of the optional last value received.
   * If the stream completes before signaling at least a single element, the value of the CompletionStage will be an empty [[java.util.Optional]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[head]].
   */
  def lastOption[In](): Sink[In, CompletionStage[Optional[In]]] =
    new Sink(scaladsl.Sink.lastOption[In].mapMaterializedValue(
      _.map(_.asJava)(ExecutionContexts.sameThreadExecutionContext).toJava))

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
   * limiting stage in front of this `Sink`.
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
    new Sink(scaladsl.Sink.actorRefWithAck[In](ref, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage.apply))

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorSubscriber]].
   */
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
    val seq = if (rest != null) rest.asScala.map(_.asScala) else Seq()
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
}

/**
 * Java API
 *
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
final class Sink[-In, +Mat](delegate: scaladsl.Sink[In, Mat]) extends Graph[SinkShape[In], Mat] {

  override def shape: SinkShape[In] = delegate.shape
  private[stream] def module: StreamLayout.Module = delegate.module

  override def toString: String = delegate.toString

  /** Converts this Sink to its Scala DSL counterpart */
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
   * Change the attributes of this [[Source]] to the given ones and seal the list
   * of attributes. This means that further calls will not be able to remove these
   * attributes, but instead add new ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def withAttributes(attr: Attributes): javadsl.Sink[In, Mat] =
    new Sink(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this Source. Further calls to `withAttributes`
   * will not remove these attributes. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def addAttributes(attr: Attributes): javadsl.Sink[In, Mat] =
    new Sink(delegate.addAttributes(attr))

  /**
   * Add a ``name`` attribute to this Flow.
   */
  override def named(name: String): javadsl.Sink[In, Mat] =
    new Sink(delegate.named(name))

  /**
   * Put an asynchronous boundary around this `Sink`
   */
  override def async: javadsl.Sink[In, Mat] =
    new Sink(delegate.async)

}
