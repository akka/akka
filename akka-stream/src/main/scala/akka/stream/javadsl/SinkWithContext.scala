/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction

import scala.annotation.unchecked.uncheckedVariance
import scala.util.Try

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import akka.actor.ActorRef
import akka.actor.ClassicActorSystemProvider
import akka.japi.function.Creator
import akka.japi.function
import akka.japi.Pair
import akka.stream._
import akka.Done
import akka.NotUsed

object SinkWithContext {

  /**
   * Creates a SinkWithContext from a regular sink that operates on `Pair<data, context>` elements.
   */
  def fromPairs[In, CtxIn, Mat](under: Sink[Pair[In, CtxIn], Mat]): SinkWithContext[In, CtxIn, Mat] =
    new SinkWithContext(under)

  /**
   * A `SinkWithContext` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   */
  def fold[U, In, Ctx](zero: Pair[U, Ctx], f: function.Function2[Pair[U, Ctx], Pair[In, Ctx], Pair[U, Ctx]])
      : SinkWithContext[In, Ctx, CompletionStage[Pair[U, Ctx]]] = {
    SinkWithContext.fromPairs(Sink.fold(zero, f))
  }

  /**
   * A `SinkWithContext` that will invoke the given asynchronous function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   */
  def foldAsync[U, In, Ctx](
      zero: Pair[U, Ctx],
      f: function.Function2[Pair[U, Ctx], Pair[In, Ctx], CompletionStage[Pair[U, Ctx]]])
      : SinkWithContext[In, Ctx, CompletionStage[Pair[U, Ctx]]] =
    SinkWithContext.fromPairs(Sink.foldAsync(zero, f))

  /**
   * A `SinkWithContext` that will invoke the given function for every received element, giving it its previous
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
  def reduce[In, Ctx](f: function.Function2[Pair[In, Ctx], Pair[In, Ctx], Pair[In, Ctx]])
      : SinkWithContext[In, Ctx, CompletionStage[Pair[In, Ctx]]] =
    SinkWithContext.fromPairs(Sink.reduce(f))

  /**
   * Helper to create [[SinkWithContext]] from `Subscriber`.
   */
  def fromSubscriber[In, Ctx](subs: Subscriber[Pair[In, Ctx]]): SinkWithContext[In, Ctx, NotUsed] =
    SinkWithContext.fromPairs(Sink.fromSubscriber(subs))

  /**
   * A `SinkWithContext` that immediately cancels its upstream after materialization.
   */
  def cancelled[In, Ctx](): SinkWithContext[In, Ctx, NotUsed] =
    SinkWithContext.fromPairs(Sink.cancelled())

  /**
   * A `SinkWithContext` that will consume the stream and discard the elements.
   */
  def ignore[In, Ctx](): SinkWithContext[In, Ctx, CompletionStage[Done]] =
    SinkWithContext.fromPairs(Sink.ignore())

  /**
   * A [[SinkWithContext]] that will always backpressure never cancel and never consume any elements from the stream.
   * */
  def never[In, Ctx](): SinkWithContext[In, Ctx, CompletionStage[Done]] =
    SinkWithContext.fromPairs(Sink.never)

  /**
   * A `SinkWithContext` that materializes into a [[org.reactivestreams.Publisher]].
   *
   * If `fanout` is `true`, the materialized `Publisher` will support multiple `Subscriber`s and
   * the size of the `inputBuffer` configured for this operator becomes the maximum number of elements that
   * the fastest [[org.reactivestreams.Subscriber]] can be ahead of the slowest one before slowing
   * the processing down due to back pressure.
   *
   * If `fanout` is `false` then the materialized `Publisher` will only support a single `Subscriber` and
   * reject any additional `Subscriber`s.
   */
  def asPublisher[In, Ctx](fanout: AsPublisher): SinkWithContext[In, Ctx, Publisher[Pair[In, Ctx]]] =
    SinkWithContext.fromPairs(Sink.asPublisher(fanout))

  /**
   * A `SinkWithContext` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[java.util.concurrent.CompletionStage]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreach[In, Ctx](f: function.Procedure2[In, Ctx]): SinkWithContext[In, Ctx, CompletionStage[Done]] =
    SinkWithContext.fromPairs(Sink.foreach((param: Pair[In, Ctx]) => (f.apply _).tupled(param.toScala)))

  /**
   * A `SinkWithContext` that will invoke the given procedure asynchronously for each received element. The sink is
   * materialized into a [[java.util.concurrent.CompletionStage]] which will be completed with `Success` when reaching
   * the normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreachAsync[In, Ctx](parallelism: Int)(
      f: function.Function2[In, Ctx, CompletionStage[Void]]): SinkWithContext[In, Ctx, CompletionStage[Done]] =
    SinkWithContext.fromPairs(
      Sink.foreachAsync(parallelism)((param: Pair[In, Ctx]) => (f.apply _).tupled(param.toScala)))

  /**
   * A `SinkWithContext` that when the flow is completed, either through a failure or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[In, Ctx](callback: function.Procedure[Try[Done]]): SinkWithContext[In, Ctx, NotUsed] =
    SinkWithContext.fromPairs(Sink.onComplete(callback))

  /**
   * A `SinkWithContext` that materializes into a `CompletionStage` of the first value received.
   * If the stream completes before signaling at least a single element, the CompletionStage will be failed with a [[NoSuchElementException]].
   * If the stream signals an error before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[headOption]].
   */
  def head[In, Ctx](): SinkWithContext[In, Ctx, CompletionStage[Pair[In, Ctx]]] =
    SinkWithContext.fromPairs(Sink.head())

  /**
   * A `SinkWithContext` that materializes into a `CompletionStage` of the optional first value received.
   * If the stream completes before signaling at least a single element, the value of the CompletionStage will be an empty [[java.util.Optional]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[head]].
   */
  def headOption[In, Ctx](): SinkWithContext[In, Ctx, CompletionStage[Optional[Pair[In, Ctx]]]] =
    SinkWithContext.fromPairs(Sink.headOption())

  /**
   * A `SinkWithContext` that materializes into a `CompletionStage` of the last value received.
   * If the stream completes before signaling at least a single element, the CompletionStage will be failed with a [[NoSuchElementException]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[lastOption]], [[takeLast]].
   */
  def last[In, Ctx](): SinkWithContext[In, Ctx, CompletionStage[Pair[In, Ctx]]] =
    SinkWithContext.fromPairs(Sink.last())

  /**
   * A `SinkWithContext` that materializes into a `CompletionStage` of the optional last value received.
   * If the stream completes before signaling at least a single element, the value of the CompletionStage will be an empty [[java.util.Optional]].
   * If the stream signals an error errors before signaling at least a single element, the CompletionStage will be failed with the streams exception.
   *
   * See also [[head]], [[takeLast]].
   */
  def lastOption[In, Ctx](): SinkWithContext[In, Ctx, CompletionStage[Optional[Pair[In, Ctx]]]] =
    SinkWithContext.fromPairs(Sink.lastOption())

  /**
   * A `SinkWithContext` that materializes into a a `CompletionStage` of `List<In>` containing the last `n` collected elements.
   *
   * If the stream completes before signaling at least n elements, the `CompletionStage` will complete with all elements seen so far.
   * If the stream never completes the `CompletionStage` will never complete.
   * If there is a failure signaled in the stream the `CompletionStage` will be completed with failure.
   */
  def takeLast[In, Ctx](n: Int): SinkWithContext[In, Ctx, CompletionStage[java.util.List[Pair[In, Ctx]]]] =
    SinkWithContext.fromPairs(Sink.takeLast(n))

  /**
   * A `SinkWithContext` that keeps on collecting incoming elements until upstream terminates.
   * As upstream may be unbounded, `Flow[Pair[In, Ctx]].take` or the stricter `Flow[Pair[In, Ctx]].limit`
   * (and their variants) may be used to ensure boundedness.
   * Materializes into a `CompletionStage` of `Seq[T]` containing all the collected elements.
   * `List` is limited to `Integer.MAX_VALUE` elements, this Sink will cancel the stream
   * after having received that many elements.
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]], [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def seq[In, Ctx]: SinkWithContext[In, Ctx, CompletionStage[java.util.List[Pair[In, Ctx]]]] =
    SinkWithContext.fromPairs(Sink.seq)

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
   * limiting operator in front of this `SinkWithContext`.
   *
   */
  def actorRef[In, Ctx](ref: ActorRef, onCompleteMessage: Any): SinkWithContext[In, Ctx, NotUsed] =
    SinkWithContext.fromPairs(Sink.actorRef(ref, onCompleteMessage))

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
  def actorRefWithBackpressure[In, Ctx](
      ref: ActorRef,
      onInitMessage: Any,
      ackMessage: Any,
      onCompleteMessage: Any,
      onFailureMessage: function.Function[Throwable, Any]): SinkWithContext[In, Ctx, NotUsed] =
    SinkWithContext.fromPairs(
      Sink.actorRefWithBackpressure(ref, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage))

  /**
   * Sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
   * First element is always `onInitMessage`, then stream is waiting for acknowledgement message
   * from the given actor which means that it is ready to process
   * elements. It also requires an ack message after each stream element
   * to make backpressure work. This variant will consider any message as ack message.
   *
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure - result of `onFailureMessage(throwable)`
   * message will be sent to the destination actor.
   */
  def actorRefWithBackpressure[In, Ctx](
      ref: ActorRef,
      onInitMessage: Any,
      onCompleteMessage: Any,
      onFailureMessage: function.Function[Throwable, Any]): SinkWithContext[In, Ctx, NotUsed] =
    SinkWithContext.fromPairs(Sink.actorRefWithBackpressure(ref, onInitMessage, onCompleteMessage, onFailureMessage))

  /**
   * Defers the creation of a [[SinkWithContext]] until materialization. The `factory` function
   * exposes [[Materializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[SinkWithContext]] returned by this method.
   */
  def fromMaterializer[In, Ctx, M](factory: BiFunction[Materializer, Attributes, SinkWithContext[In, Ctx, M]])
      : SinkWithContext[In, Ctx, CompletionStage[M]] =
    SinkWithContext.fromPairs(Sink.fromMaterializer((mat, attr) => factory(mat, attr).asSink))

  /**
   * Combine several sinks with fan-out strategy like `Broadcast` or `Balance` and returns `Sink`.
   */
  def combine[T, U, Ctx](
      output1: SinkWithContext[U, Ctx, _],
      output2: SinkWithContext[U, Ctx, _],
      rest: java.util.List[SinkWithContext[U, Ctx, _]],
      strategy: function.Function[java.lang.Integer, Graph[UniformFanOutShape[Pair[T, Ctx], Pair[U, Ctx]], NotUsed]])
      : SinkWithContext[T, Ctx, NotUsed] = {
    val replaced = new util.ArrayList[Sink[Pair[U, Ctx], _]](rest.size())
    val it = rest.iterator()
    while (it.hasNext) replaced.add(it.next().asSink)

    SinkWithContext.fromPairs(Sink.combine(output1.asSink, output2.asSink, replaced, strategy))
  }

  /**
   * Creates a `SinkWithContext` that is materialized as an [[akka.stream.javadsl.SinkQueueWithCancel]].
   * [[akka.stream.javadsl.SinkQueueWithCancel.pull]] method is pulling element from the stream and returns ``CompletionStage[Option[Pair[In, Ctx]]]``.
   * `CompletionStage` completes when element is available.
   *
   * Before calling pull method second time you need to ensure that number of pending pulls is less then ``maxConcurrentPulls``
   * or wait until some of the previous Futures completes.
   * Pull returns Failed future with ''IllegalStateException'' if there will be more then ``maxConcurrentPulls`` number of pending pulls.
   *
   * `SinkWithContext` will request at most number of elements equal to size of `inputBuffer` from
   * upstream and then stop back pressure.  You can configure size of input
   * buffer by using [[SinkWithContext.withAttributes]] method.
   *
   * For stream completion you need to pull all elements from [[akka.stream.javadsl.SinkQueueWithCancel]] including last None
   * as completion marker
   *
   * @see [[akka.stream.javadsl.SinkQueueWithCancel]]
   */
  def queue[In, Ctx](maxConcurrentPulls: Int): SinkWithContext[In, Ctx, SinkQueueWithCancel[Pair[In, Ctx]]] =
    SinkWithContext.fromPairs(Sink.queue(maxConcurrentPulls))

  /**
   * Creates a `SinkWithContext` that is materialized as an [[akka.stream.javadsl.SinkQueueWithCancel]].
   * [[akka.stream.javadsl.SinkQueueWithCancel.pull]] method is pulling element from the stream and returns ``CompletionStage[Option[Pair[In, Ctx]]]``.
   * `CompletionStage` completes when element is available.
   *
   * Before calling pull method second time you need to wait until previous CompletionStage completes.
   * Pull returns Failed future with ''IllegalStateException'' if previous future has not yet completed.
   *
   * `SinkWithContext` will request at most number of elements equal to size of `inputBuffer` from
   * upstream and then stop back pressure.  You can configure size of input
   * buffer by using [[SinkWithContext.withAttributes]] method.
   *
   * For stream completion you need to pull all elements from [[akka.stream.javadsl.SinkQueueWithCancel]] including last None
   * as completion marker
   *
   * @see [[akka.stream.javadsl.SinkQueueWithCancel]]
   */
  def queue[In, Ctx](): SinkWithContext[In, Ctx, SinkQueueWithCancel[Pair[In, Ctx]]] = queue(1)

  /**
   * Turn a `Future[SinkWithContext]` into a SinkWithContext that will consume the values of the source when the future completes successfully.
   * If the `Future` is completed with a failure the stream is failed.
   *
   * The materialized future value is completed with the materialized value of the future sink or failed with a
   * [[NeverMaterializedException]] if upstream fails or downstream cancels before the future has completed.
   */
  def completionStageSink[In, Ctx, M](
      future: CompletionStage[SinkWithContext[In, Ctx, M]]): SinkWithContext[In, Ctx, CompletionStage[M]] =
    SinkWithContext.fromPairs(Sink.completionStageSink(future.thenApply(_.asSink)))

  /**
   * Defers invoking the `create` function to create a sink until there is a first element passed from upstream.
   *
   * The materialized future value is completed with the materialized value of the created sink when that has successfully
   * been materialized.
   *
   * If the `create` function throws or returns or the stream fails to materialize, in this
   * case the materialized future value is failed with a [[akka.stream.NeverMaterializedException]].
   */
  def lazySink[In, Ctx, M](create: Creator[SinkWithContext[In, Ctx, M]]): SinkWithContext[In, Ctx, CompletionStage[M]] =
    SinkWithContext.fromPairs(Sink.lazySink(() => create.create().asSink))

  /**
   * Defers invoking the `create` function to create a future sink until there is a first element passed from upstream.
   *
   * The materialized future value is completed with the materialized value of the created sink when that has successfully
   * been materialized.
   *
   * If the `create` function throws or returns a future that is failed, or the stream fails to materialize, in this
   * case the materialized future value is failed with a [[akka.stream.NeverMaterializedException]].
   */
  def lazyCompletionStageSink[In, Ctx, M](
      create: Creator[CompletionStage[SinkWithContext[In, Ctx, M]]]): SinkWithContext[In, Ctx, CompletionStage[M]] =
    SinkWithContext.fromPairs(Sink.lazyCompletionStageSink(() => create.create().thenApply(_.asSink)))
}

/**
 * A Sink that indicates it accepts context along with data portion of a stream typically coming from a
 * [[FlowWithContext]] or a [[SourceWithContext]]. Since a Sink is meant to be the last part of a stream that
 * doesn't have an output, filtering/reordering incoming elements is acceptable albeit extremely unusual.
 *
 * The [[SinkWithContext]] contains the same creation methods as the standard [[Sink]] and one can also create a
 * [[SinkWithContext]] from an existing [[Sink]] containing a context by using `SinkWithContext.fromPairs`.
 *
 */
final class SinkWithContext[In, CtxIn, +Mat](delegate: Sink[Pair[In, CtxIn], Mat]) extends GraphDelegate(delegate) {

  /**
   * Converts this SinkWithContext to its Scala DSL counterpart.
   */
  def asScala: scaladsl.SinkWithContext[In, CtxIn, Mat] = {
    scaladsl.SinkWithContext.fromTuples(
      scaladsl
        .Flow[(In, CtxIn)]
        .toMat(delegate.contramap[(In, CtxIn)] {
          case (in, ctxIn) =>
            Pair(in, ctxIn)
        }) { case (_, mat) => mat })
  }

  /**
   * Connect this `SinkWithContext` to a `SourceWithContext` and run it.
   *
   * Note that the `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def runWith[M](source: Graph[SourceShape[(In, CtxIn)], M], systemProvider: ClassicActorSystemProvider): M =
    asScala.runWith(source)(SystemMaterializer(systemProvider.classicSystem).materializer)

  /**
   * Connect this `SinkWithContext` to a `SourceWithContext` and run it.
   */
  def runWith[M](source: Graph[SourceShape[(In, CtxIn)], M], materializer: Materializer): M =
    asScala.runWith(source)(materializer)

  /**
   * Transform this SinkWithContext by applying a function to each *incoming* upstream element before
   * it is passed to the [[SinkWithContext]]
   *
   * '''Backpressures when''' original [[SinkWithContext]] backpressures
   *
   * '''Cancels when''' original [[SinkWithContext]] backpressures
   */
  def contramap[In2, Ctx2](f: function.Function2[In2, Ctx2, Pair[In, CtxIn]]): SinkWithContext[In2, Ctx2, Mat] =
    viaScala(_.contraMap((in2, ctx2) => f(in2, ctx2).toScala))

  /**
   * Transform the data portion of this SinkWithContext by applying a function to each *incoming* upstream element
   * before it is passed to the [[SinkWithContext]]
   *
   * '''Backpressures when''' original [[SinkWithContext]] backpressures
   *
   * '''Cancels when''' original [[SinkWithContext]] cancels
   */
  def contramapData[In2](f: function.Function[In2, In]): SinkWithContext[In2, CtxIn, Mat] =
    viaScala(_.contramapData(in2 => f(in2)))

  /**
   * Transform only the materialized value of this SinkWithContext, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): SinkWithContext[In, CtxIn, Mat2] =
    SinkWithContext.fromPairs(delegate.mapMaterializedValue(f))

  /**
   * Materializes this SinkWithContext, immediately returning (1) its materialized value, and (2) a new SinkWithContext
   * that can be consume elements 'into' the pre-materialized one.
   *
   * Useful for when you need a materialized value of a SinkWithContext when handing it out to someone to materialize it for you.
   *
   * Note that the `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def preMaterialize(systemProvider: ClassicActorSystemProvider)
      : Pair[Mat @uncheckedVariance, SinkWithContext[In @uncheckedVariance, CtxIn @uncheckedVariance, NotUsed]] = {
    val result = delegate.preMaterialize(systemProvider)
    Pair(result.first, SinkWithContext.fromPairs(result.second))
  }

  /**
   * Materializes this SinkWithContext, immediately returning (1) its materialized value, and (2) a new SinkWithContext
   * that can be consume elements 'into' the pre-materialized one.
   *
   * Useful for when you need a materialized value of a SinkWithContext when handing it out to someone to materialize it for you.
   *
   * Prefer the method taking an ActorSystem unless you have special requirements.
   */
  def preMaterialize(materializer: Materializer)
      : Pair[Mat @uncheckedVariance, SinkWithContext[In @uncheckedVariance, CtxIn @uncheckedVariance, NotUsed]] = {
    val result = delegate.preMaterialize(materializer)
    Pair(result.first, SinkWithContext.fromPairs(result.second))
  }

  /**
   * Context-preserving variant of [[akka.stream.javadsl.Sink.withAttributes]].
   *
   * @see [[akka.stream.javadsl.Sink.withAttributes]]
   */
  override def withAttributes(attr: Attributes): SinkWithContext[In, CtxIn, Mat] =
    viaScala(_.withAttributes(attr))

  /**
   * Put an asynchronous boundary around this `SinkWithContext`
   */
  override def async: SinkWithContext[In, CtxIn, Mat] =
    viaScala(_.async)

  /**
   * Put an asynchronous boundary around this `SinkWithContext`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): SinkWithContext[In, CtxIn, Mat] =
    viaScala(_.async(dispatcher))

  /**
   * Put an asynchronous boundary around this `SinkWithContext`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): SinkWithContext[In, CtxIn, Mat] =
    viaScala(_.async(dispatcher, inputBufferSize))

  def asSink: Sink[Pair[In, CtxIn], Mat @uncheckedVariance] = delegate

  private[this] def viaScala[In2, CtxIn2, Mat2](
      f: scaladsl.SinkWithContext[In, CtxIn, Mat] => scaladsl.SinkWithContext[In2, CtxIn2, Mat2])
      : SinkWithContext[In2, CtxIn2, Mat2] =
    f(this.asScala).asJava
}
