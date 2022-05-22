/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import akka.actor.ActorRef
import akka.Done
import akka.NotUsed
import akka.dispatch.ExecutionContexts
import akka.japi.Pair
import akka.stream._
import akka.util.ccompat.Factory

object SinkWithContext {

  /**
   * Creates a SinkWithContext from a regular sink that operates on a tuple of `(data, context)` elements.
   */
  def fromTuples[In, CtxIn, Mat](sink: Sink[(In, CtxIn), Mat]): SinkWithContext[In, CtxIn, Mat] =
    new SinkWithContext(sink)

  /**
   * Creates a SinkWithContext from a data sink and a context sink with a fan-in strategy like `Concat` which
   * controls interaction between the `dataSink` and `contextSink`.
   * @param dataComesFirst Whether the data sink is fed first into the strategy or the context sink. Important for
   *                       strategies such as Combine where ordering matters
   * @return
   */
  def fromDataAndContext[In, CtxIn, MatIn, MatCtx, Mat3](
      dataSink: Graph[SinkShape[In], MatIn],
      contextSink: Graph[SinkShape[CtxIn], MatCtx],
      combine: (MatIn, MatCtx) => Mat3,
      dataComesFirst: Boolean = true)(
      strategy: Int => Graph[UniformFanInShape[Either[In, CtxIn], Either[In, CtxIn]], NotUsed])
      : SinkWithContext[In, CtxIn, Mat3] = {
    SinkWithContext.fromTuples(Sink.fromGraph(GraphDSL.createGraph(dataSink, contextSink)(combine) {
      implicit b => (dSink, ctxSink) =>
        import GraphDSL.Implicits._

        val unzip = b.add(Unzip[In, CtxIn]())

        val c = b.add(strategy(2))
        val p = b.add(new Partition[Either[In, CtxIn]](outputPorts = 2, { in =>
          if (in.isLeft)
            0
          else
            1
        }, eagerCancel = true))

        if (dataComesFirst) {
          unzip.out0.map(Left.apply) ~> c.in(0)
          unzip.out1.map(Right.apply) ~> c.in(1)
        } else {
          unzip.out1.map(Right.apply) ~> c.in(0)
          unzip.out0.map(Left.apply) ~> c.in(1)
        }

        c.out ~> p.in

        p.out(0).map(_.asInstanceOf[Left[In, CtxIn]].value) ~> dSink.in
        p.out(1).map(_.asInstanceOf[Right[In, CtxIn]].value) ~> ctxSink.in

        SinkShape(unzip.in)
    }))
  }

  /**
   * Defers the creation of a [[SinkWithContext]] until materialization. The `factory` function
   * exposes [[Materializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[SinkWithContext]] returned by this method.
   */
  def fromMaterializer[T, C, M](
      factory: (Materializer, Attributes) => SinkWithContext[T, C, M]): SinkWithContext[T, C, Future[M]] =
    new SinkWithContext(Sink.fromMaterializer((mat, attr) => factory(mat, attr).asSink))

  /**
   * Helper to create [[SinkWithContext]] from `Subscriber`.
   */
  def fromSubscriber[T, C](subscriber: Subscriber[(T, C)]): SinkWithContext[T, C, NotUsed] =
    new SinkWithContext(Sink.fromSubscriber(subscriber))

  /**
   * A `SinkWithContext` that immediately cancels its upstream after materialization.
   */
  def cancelled[T, C]: SinkWithContext[T, C, NotUsed] =
    new SinkWithContext(Sink.cancelled)

  /**
   * A `SinkWithContext` that materializes into a `Future` of the first value received.
   * If the stream completes before signaling at least a single element, the Future will be failed with a [[NoSuchElementException]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[headOption]].
   */
  def head[T, C]: SinkWithContext[T, C, Future[(T, C)]] =
    new SinkWithContext(Sink.head)

  /**
   * A `SinkWithContext` that materializes into a `Future` of the optional first value received.
   * If the stream completes before signaling at least a single element, the value of the Future will be [[None]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[head]].
   */
  def headOption[T, C]: SinkWithContext[T, C, Future[Option[(T, C)]]] =
    new SinkWithContext(Sink.headOption)

  /**
   * A `SinkWithContext` that materializes into a `Future` of the last value received.
   * If the stream completes before signaling at least a single element, the Future will be failed with a [[NoSuchElementException]].
   * If the stream signals an error, the Future will be failed with the stream's exception.
   *
   * See also [[lastOption]], [[takeLast]].
   */
  def last[T, C]: SinkWithContext[T, C, Future[(T, C)]] =
    new SinkWithContext(Sink.last[(T, C)])

  /**
   * A `SinkWithContext` that materializes into a `Future` of the optional last value received.
   * If the stream completes before signaling at least a single element, the value of the Future will be [[None]].
   * If the stream signals an error, the Future will be failed with the stream's exception.
   *
   * See also [[last]], [[takeLast]].
   */
  def lastOption[T, C]: SinkWithContext[T, C, Future[Option[(T, C)]]] =
    new SinkWithContext(Sink.lastOption)

  /**
   * A `SinkWithContext` that materializes into a a `Future` of `immutable.Seq[(T,C)]` containing the last `n` collected elements.
   *
   * If the stream completes before signaling at least n elements, the `Future` will complete with all elements seen so far.
   * If the stream never completes, the `Future` will never complete.
   * If there is a failure signaled in the stream the `Future` will be completed with failure.
   */
  def takeLast[T, C](n: Int): SinkWithContext[T, C, Future[immutable.Seq[(T, C)]]] =
    new SinkWithContext(Sink.takeLast(n))

  /**
   * A `SinkWithContext` that keeps on collecting incoming elements until upstream terminates.
   * As upstream may be unbounded, `Flow[(T,C)].take` or the stricter `Flow[(T,C)].limit` (and their variants)
   * may be used to ensure boundedness.
   * Materializes into a `Future` of `Seq[(T,C)]` containing all the collected elements.
   * `Seq` is limited to `Int.MaxValue` elements, this SinkWithContext will cancel the stream
   * after having received that many elements.
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]], [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def seq[T, C]: SinkWithContext[T, C, Future[immutable.Seq[(T, C)]]] =
    new SinkWithContext(Sink.seq)

  /**
   * A `SinkWithContext` that keeps on collecting incoming elements until upstream terminates.
   * As upstream may be unbounded, `Flow[(T,C)].take` or the stricter `Flow[(T,C)].limit` (and their variants)
   * may be used to ensure boundedness.
   * Materializes into a `Future` of `That[T]` containing all the collected elements.
   * `That[T]` is limited to the limitations of the CanBuildFrom associated with it. For example, `Seq` is limited to
   * `Int.MaxValue` elements. See [The Architecture of Scala 2.13's Collections](https://docs.scala-lang.org/overviews/core/architecture-of-scala-213-collections.html) for more info.
   * This SinkWithContext will cancel the stream after having received that many elements.
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]], [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def collection[T, C, That](
      implicit cbf: Factory[(T, C), That with immutable.Iterable[_]]): SinkWithContext[T, C, Future[That]] =
    new SinkWithContext(Sink.collection[(T, C), That])

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
  def asPublisher[T, C](fanout: Boolean): SinkWithContext[T, C, Publisher[(T, C)]] =
    new SinkWithContext(Sink.asPublisher(fanout))

  /**
   * A `SinkWithContext` that will consume the stream and discard the elements.
   */
  def ignore: SinkWithContext[Any, Any, Future[Done]] =
    new SinkWithContext(Sink.ignore)

  /**
   * A [[SinkWithContext]] that will always backpressure never cancel and never consume any elements from the stream.
   * */
  def never: SinkWithContext[Any, Any, Future[Done]] =
    new SinkWithContext(Sink.never)

  /**
   * A `SinkWithContext` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreach[T, C](f: (T, C) => Unit): SinkWithContext[T, C, Future[Done]] =
    new SinkWithContext(Sink.foreach(f.tupled))

  /**
   * A `SinkWithContext` that will invoke the given procedure asynchronously for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreachAsync[T, C](parallelism: Int)(f: (T, C) => Future[Unit]): SinkWithContext[T, C, Future[Done]] =
    new SinkWithContext(Sink.foreachAsync(parallelism)(f.tupled))

  /**
   * Combine several sinks with fan-out strategy like `Broadcast` or `Balance` and returns `SinkWithContext`.
   */
  def combine[T, U, C](
      first: SinkWithContext[U, C, _],
      second: SinkWithContext[U, C, _],
      rest: SinkWithContext[U, C, _]*)(
      strategy: Int => Graph[UniformFanOutShape[(T, C), (U, C)], NotUsed]): SinkWithContext[T, C, NotUsed] =
    new SinkWithContext(Sink.combine(first.asSink, second.asSink, rest.map(_.asSink): _*)(strategy))

  /**
   * A `SinkWithContext` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * @see [[#foldAsync]]
   */
  def fold[U, T, C](zero: (U, C))(f: ((U, C), (T, C)) => (U, C)): SinkWithContext[T, C, Future[(U, C)]] =
    new SinkWithContext(Sink.fold(zero)(f))

  /**
   * A `SinkWithContext` that will invoke the given asynchronous function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * @see [[#fold]]
   */
  def foldAsync[U, T, C](zero: (U, C))(f: ((U, C), (T, C)) => Future[(U, C)]): SinkWithContext[T, C, Future[(U, C)]] =
    new SinkWithContext(Sink.foldAsync(zero)(f))

  /**
   * A `SinkWithContext` that will invoke the given function for every received element, giving it its previous
   * output (from the second element) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   */
  def reduce[T, C](f: ((T, C), (T, C)) => (T, C)): SinkWithContext[T, C, Future[(T, C)]] =
    new SinkWithContext(Sink.reduce(f))

  /**
   * A `SinkWithContext` that when the flow is completed, either through a failure or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[T, C](callback: Try[Done] => Unit): SinkWithContext[T, C, NotUsed] =
    new SinkWithContext(Sink.onComplete(callback))

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
   * function will be sent to the destination actor.
   */
  def actorRefWithBackpressure[T, C](
      ref: ActorRef,
      onInitMessage: Any,
      ackMessage: Any,
      onCompleteMessage: Any,
      onFailureMessage: Throwable => Any): SinkWithContext[T, C, NotUsed] =
    new SinkWithContext(
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
   * function will be sent to the destination actor.
   */
  def actorRefWithBackpressure[T, C](
      ref: ActorRef,
      onInitMessage: Any,
      onCompleteMessage: Any,
      onFailureMessage: Throwable => Any): SinkWithContext[T, C, NotUsed] =
    new SinkWithContext(Sink.actorRefWithBackpressure(ref, onInitMessage, onCompleteMessage, onFailureMessage))

  /**
   * Creates a `SinkWithContext` that is materialized as an [[akka.stream.scaladsl.SinkQueueWithCancel]].
   * [[akka.stream.scaladsl.SinkQueueWithCancel.pull]] method is pulling element from the stream and returns ``Future[Option[(T,C)]``.
   * `Future` completes when element is available.
   *
   * Before calling pull method second time you need to ensure that number of pending pulls is less then ``maxConcurrentPulls``
   * or wait until some of the previous Futures completes.
   * Pull returns Failed future with ''IllegalStateException'' if there will be more then ``maxConcurrentPulls`` number of pending pulls.
   *
   * `SinkWithContext` will request at most number of elements equal to size of `inputBuffer` from
   * upstream and then stop back pressure.  You can configure size of input
   * buffer by using [[SinkWithContext.withAttributes]] method.
   *
   * For stream completion you need to pull all elements from [[akka.stream.scaladsl.SinkQueueWithCancel]] including last None
   * as completion marker
   *
   * See also [[akka.stream.scaladsl.SinkQueueWithCancel]]
   */
  def queue[T, C](maxConcurrentPulls: Int): SinkWithContext[T, C, SinkQueueWithCancel[(T, C)]] =
    new SinkWithContext(Sink.queue[(T, C)](maxConcurrentPulls))

  /**
   * Creates a `SinkWithContext` that is materialized as an [[akka.stream.scaladsl.SinkQueueWithCancel]].
   * [[akka.stream.scaladsl.SinkQueueWithCancel.pull]] method is pulling element from the stream and returns ``Future[Option[(T,C)]]``.
   * `Future` completes when element is available.
   *
   * Before calling pull method second time you need to wait until previous Future completes.
   * Pull returns Failed future with ''IllegalStateException'' if previous future has not yet completed.
   *
   * `SinkWithContext` will request at most number of elements equal to size of `inputBuffer` from
   * upstream and then stop back pressure.  You can configure size of input
   * buffer by using [[SinkWithContext.withAttributes]] method.
   *
   * For stream completion you need to pull all elements from [[akka.stream.scaladsl.SinkQueueWithCancel]] including last None
   * as completion marker
   *
   * See also [[akka.stream.scaladsl.SinkQueueWithCancel]]
   */
  def queue[T, C](): SinkWithContext[T, C, SinkQueueWithCancel[(T, C)]] = queue(1)

  /**
   * Turn a `Future[SinkWithContext]` into a SinkWithContext that will consume the values of the source when the future completes successfully.
   * If the `Future` is completed with a failure the stream is failed.
   *
   * The materialized future value is completed with the materialized value of the future sink or failed with a
   * [[NeverMaterializedException]] if upstream fails or downstream cancels before the future has completed.
   */
  def futureSink[T, C, M](future: Future[SinkWithContext[T, C, M]]): SinkWithContext[T, C, Future[M]] =
    lazyFutureSink[T, C, M](() => future)

  /**
   * Defers invoking the `create` function to create a sink until there is a first element passed from upstream.
   *
   * The materialized future value is completed with the materialized value of the created sink when that has successfully
   * been materialized.
   *
   * If the `create` function throws or returns or the stream fails to materialize, in this
   * case the materialized future value is failed with a [[akka.stream.NeverMaterializedException]].
   */
  def lazySink[T, C, M](create: () => SinkWithContext[T, C, M]): SinkWithContext[T, C, Future[M]] =
    lazyFutureSink(() => Future.successful(create()))

  /**
   * Defers invoking the `create` function to create a future sink until there is a first element passed from upstream.
   *
   * The materialized future value is completed with the materialized value of the created sink when that has successfully
   * been materialized.
   *
   * If the `create` function throws or returns a future that is failed, or the stream fails to materialize, in this
   * case the materialized future value is failed with a [[akka.stream.NeverMaterializedException]].
   */
  def lazyFutureSink[T, C, M](create: () => Future[SinkWithContext[T, C, M]]): SinkWithContext[T, C, Future[M]] =
    new SinkWithContext(Sink.lazyFutureSink(() => create().map(_.asSink)(ExecutionContexts.parasitic)))
}

/**
 * A Sink that indicates it accepts context along with data portion of a stream typically coming from a
 * [[FlowWithContext]] or a [[SourceWithContext]]. Since a Sink is meant to be the last part of a stream that
 * doesn't have an output, filtering/reordering incoming elements is acceptable albeit extremely unusual.
 *
 * The [[SinkWithContext]] contains the same creation methods as the standard [[Sink]] and one can also create a
 * [[SinkWithContext]] from an existing [[Sink]] containing a context by using `SinkWithContext.fromTuples`.
 *
 */
final class SinkWithContext[-In, -CtxIn, +Mat](delegate: Sink[(In, CtxIn), Mat]) extends GraphDelegate(delegate) {

  /**
   * Transform this SinkWithContext by applying a function to each *incoming* upstream element before
   * it is passed to the [[SinkWithContext]]
   *
   * '''Backpressures when''' original [[SinkWithContext]] backpressures
   *
   * '''Cancels when''' original [[SinkWithContext]] cancels
   */
  def contraMap[In2, Ctx2](f: (In2, Ctx2) => (In, CtxIn)): SinkWithContext[In2, Ctx2, Mat] =
    new SinkWithContext(delegate.contramap[(In2, Ctx2)](f.tupled))

  /**
   * Transform the data portion of this SinkWithContext by applying a function to each *incoming* upstream element
   * before it is passed to the [[SinkWithContext]]
   *
   * '''Backpressures when''' original [[SinkWithContext]] backpressures
   *
   * '''Cancels when''' original [[SinkWithContext]] cancels
   */
  def contramapData[In2](f: In2 => In): SinkWithContext[In2, CtxIn, Mat] = {
    val applyOnIn = (tuple: (In2, CtxIn)) => (f(tuple._1), tuple._2)
    new SinkWithContext(delegate.contramap(applyOnIn))
  }

  /**
   * Connect this `SinkWithContext` to a `SourceWithContext` or a Source[(T,C)] and run it. The returned value is
   * the materialized value of the `SourceWithContext`/`Source`, e.g. the `Subscriber` of a [[Source#subscriber]].
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[akka.stream.SystemMaterializer]] for running the stream.
   */
  def runWith[Mat2](source: Graph[SourceShape[(In, CtxIn)], Mat2])(implicit materializer: Materializer): Mat2 =
    delegate.runWith(source)

  /**
   * Transform only the materialized value of this SinkWithContext, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat => Mat2): SinkWithContext[In, CtxIn, Mat2] =
    new SinkWithContext(delegate.mapMaterializedValue(f))

  /**
   * Materializes this SinkWithContext, immediately returning (1) its materialized value, and (2) a new SinkWithContext
   * that can be consume elements 'into' the pre-materialized one.
   *
   * Useful for when you need a materialized value of a SinkWithContext when handing it out to someone to materialize it for you.
   */
  def preMaterialize()(implicit materializer: Materializer): (Mat, SinkWithContext[In, CtxIn, NotUsed]) = {
    val (mat, sink) = delegate.preMaterialize()
    (mat, new SinkWithContext(sink))
  }

  /**
   * Context-preserving variant of [[akka.stream.scaladsl.Flow.withAttributes]].
   *
   * @see [[akka.stream.scaladsl.Flow.withAttributes]]
   */
  override def withAttributes(attr: Attributes): SinkWithContext[In, CtxIn, Mat] =
    new SinkWithContext(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this [[SinkWithContext]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this SinkWithContext is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): SinkWithContext[In, CtxIn, Mat] =
    withAttributes(traversalBuilder.attributes and attr)

  /**
   * Put an asynchronous boundary around this `SinkWithContext`
   */
  override def async: SinkWithContext[In, CtxIn, Mat] = super.async.asInstanceOf[SinkWithContext[In, CtxIn, Mat]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): SinkWithContext[In, CtxIn, Mat] =
    super.async(dispatcher).asInstanceOf[SinkWithContext[In, CtxIn, Mat]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): SinkWithContext[In, CtxIn, Mat] =
    super.async(dispatcher, inputBufferSize).asInstanceOf[SinkWithContext[In, CtxIn, Mat]]

  def asSink: Sink[(In, CtxIn), Mat] = delegate

  def asJava[JIn <: In, JCtxIn <: CtxIn, JMat >: Mat]: javadsl.SinkWithContext[JIn, JCtxIn, JMat] = {
    new javadsl.SinkWithContext(
      javadsl.Flow
        .create[Pair[JIn, JCtxIn]]()
        .toMat(delegate.contramap[Pair[JIn, JCtxIn]](_.toScala), (_, mat: Mat) => mat))
  }

  override def getAttributes: Attributes = traversalBuilder.attributes
}
