/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.{ Done, NotUsed }
import akka.dispatch.ExecutionContexts
import akka.actor.{ ActorRef, Props, Status }
import akka.annotation.InternalApi
import akka.stream.actor.ActorSubscriber
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl._
import akka.stream.impl.fusing.GraphStages
import akka.stream.stage._
import akka.stream.{ javadsl, _ }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import scala.collection.immutable
import akka.util.ccompat._

import scala.annotation.unchecked.uncheckedVariance

/**
 * A `Sink` is a set of stream processing steps that has one open input.
 * Can be used as a `Subscriber`
 */
final class Sink[-In, +Mat](
  override val traversalBuilder: LinearTraversalBuilder,
  override val shape:            SinkShape[In])
  extends Graph[SinkShape[In], Mat] {

  // TODO: Debug string
  override def toString: String = s"Sink($shape)"

  /**
   * Transform this Sink by applying a function to each *incoming* upstream element before
   * it is passed to the [[Sink]]
   *
   * '''Backpressures when''' original [[Sink]] backpressures
   *
   * '''Cancels when''' original [[Sink]] cancels
   */
  def contramap[In2](f: In2 ⇒ In): Sink[In2, Mat] = Flow.fromFunction(f).toMat(this)(Keep.right)

  /**
   * Connect this `Sink` to a `Source` and run it. The returned value is the materialized value
   * of the `Source`, e.g. the `Subscriber` of a [[Source#subscriber]].
   */
  def runWith[Mat2](source: Graph[SourceShape[In], Mat2])(implicit materializer: Materializer): Mat2 =
    Source.fromGraph(source).to(this).run()

  /**
   * Transform only the materialized value of this Sink, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): Sink[In, Mat2] =
    new Sink(
      traversalBuilder.transformMat(f.asInstanceOf[Any ⇒ Any]),
      shape)

  /**
   * Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink
   * that can be consume elements 'into' the pre-materialized one.
   *
   * Useful for when you need a materialized value of a Sink when handing it out to someone to materialize it for you.
   */
  def preMaterialize()(implicit materializer: Materializer): (Mat, Sink[In, NotUsed]) = {
    val (sub, mat) = Source.asSubscriber.toMat(this)(Keep.both).run()
    (mat, Sink.fromSubscriber(sub))
  }

  /**
   * Replace the attributes of this [[Sink]] with the given ones. If this Sink is a composite
   * of multiple graphs, new attributes on the composite will be less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def withAttributes(attr: Attributes): Sink[In, Mat] =
    new Sink(
      traversalBuilder.setAttributes(attr),
      shape)

  /**
   * Add the given attributes to this [[Sink]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this Sink is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): Sink[In, Mat] =
    withAttributes(traversalBuilder.attributes and attr)

  /**
   * Add a ``name`` attribute to this Sink.
   */
  override def named(name: String): Sink[In, Mat] = addAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Source`
   */
  override def async: Sink[In, Mat] = super.async.asInstanceOf[Sink[In, Mat]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): Sink[In, Mat] =
    super.async(dispatcher).asInstanceOf[Sink[In, Mat]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): Sink[In, Mat] =
    super.async(dispatcher, inputBufferSize).asInstanceOf[Sink[In, Mat]]

  /**
   * Converts this Scala DSL element to it's Java DSL counterpart.
   */
  def asJava[JIn <: In]: javadsl.Sink[JIn, Mat @uncheckedVariance] = new javadsl.Sink(this)
}

object Sink {

  /** INTERNAL API */
  def shape[T](name: String): SinkShape[T] = SinkShape(Inlet(name + ".in"))

  /**
   * A graph with the shape of a sink logically is a sink, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SinkShape[T], M]): Sink[T, M] =
    g match {
      case s: Sink[T, M]         ⇒ s
      case s: javadsl.Sink[T, M] ⇒ s.asScala
      case g: GraphStageWithMaterializedValue[SinkShape[T], M] ⇒
        // move these from the stage itself to make the returned source
        // behave as it is the stage with regards to attributes
        val attrs = g.traversalBuilder.attributes
        val noAttrStage = g.withAttributes(Attributes.none)
        new Sink(
          LinearTraversalBuilder.fromBuilder(noAttrStage.traversalBuilder, noAttrStage.shape, Keep.right),
          noAttrStage.shape
        ).withAttributes(attrs)

      case other ⇒ new Sink(
        LinearTraversalBuilder.fromBuilder(other.traversalBuilder, other.shape, Keep.right),
        other.shape)
    }

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def fromSubscriber[T](subscriber: Subscriber[T]): Sink[T, NotUsed] =
    fromGraph(new SubscriberSink(subscriber, DefaultAttributes.subscriberSink, shape("SubscriberSink")))

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Sink[T, NotUsed] =
    fromGraph[Any, NotUsed](new CancelSink(DefaultAttributes.cancelledSink, shape("CancelledSink")))

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   * If the stream completes before signaling at least a single element, the Future will be failed with a [[NoSuchElementException]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[headOption]].
   */
  def head[T]: Sink[T, Future[T]] =
    Sink.fromGraph(new HeadOptionStage[T]).withAttributes(DefaultAttributes.headSink)
      .mapMaterializedValue(e ⇒ e.map(_.getOrElse(throw new NoSuchElementException("head of empty stream")))(ExecutionContexts.sameThreadExecutionContext))

  /**
   * A `Sink` that materializes into a `Future` of the optional first value received.
   * If the stream completes before signaling at least a single element, the value of the Future will be [[None]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[head]].
   */
  def headOption[T]: Sink[T, Future[Option[T]]] =
    Sink.fromGraph(new HeadOptionStage[T]).withAttributes(DefaultAttributes.headOptionSink)

  /**
   * A `Sink` that materializes into a `Future` of the last value received.
   * If the stream completes before signaling at least a single element, the Future will be failed with a [[NoSuchElementException]].
   * If the stream signals an error, the Future will be failed with the stream's exception.
   *
   * See also [[lastOption]], [[takeLast]].
   */
  def last[T]: Sink[T, Future[T]] = {
    Sink.fromGraph(new TakeLastStage[T](1)).withAttributes(DefaultAttributes.lastSink)
      .mapMaterializedValue { e ⇒
        e.map(_.headOption.getOrElse(throw new NoSuchElementException("last of empty stream")))(ExecutionContexts.sameThreadExecutionContext)
      }
  }

  /**
   * A `Sink` that materializes into a `Future` of the optional last value received.
   * If the stream completes before signaling at least a single element, the value of the Future will be [[None]].
   * If the stream signals an error, the Future will be failed with the stream's exception.
   *
   * See also [[last]], [[takeLast]].
   */
  def lastOption[T]: Sink[T, Future[Option[T]]] = {
    Sink.fromGraph(new TakeLastStage[T](1)).withAttributes(DefaultAttributes.lastOptionSink)
      .mapMaterializedValue { e ⇒
        e.map(_.headOption)(ExecutionContexts.sameThreadExecutionContext)
      }
  }

  /**
   * A `Sink` that materializes into a a `Future` of `immutable.Seq[T]` containing the last `n` collected elements.
   *
   * If the stream completes before signaling at least n elements, the `Future` will complete with all elements seen so far.
   * If the stream never completes, the `Future` will never complete.
   * If there is a failure signaled in the stream the `Future` will be completed with failure.
   */
  def takeLast[T](n: Int): Sink[T, Future[immutable.Seq[T]]] =
    Sink.fromGraph(new TakeLastStage[T](n)).withAttributes(DefaultAttributes.takeLastSink)

  /**
   * A `Sink` that keeps on collecting incoming elements until upstream terminates.
   * As upstream may be unbounded, `Flow[T].take` or the stricter `Flow[T].limit` (and their variants)
   * may be used to ensure boundedness.
   * Materializes into a `Future` of `Seq[T]` containing all the collected elements.
   * `Seq` is limited to `Int.MaxValue` elements, this Sink will cancel the stream
   * after having received that many elements.
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]], [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def seq[T]: Sink[T, Future[immutable.Seq[T]]] = Sink.fromGraph(new SeqStage[T, Vector[T]])

  /**
   * A `Sink` that keeps on collecting incoming elements until upstream terminates.
   * As upstream may be unbounded, `Flow[T].take` or the stricter `Flow[T].limit` (and their variants)
   * may be used to ensure boundedness.
   * Materializes into a `Future` of `That[T]` containing all the collected elements.
   * `That[T]` is limited to the limitations of the CanBuildFrom associated with it. For example, `Seq` is limited to
   * `Int.MaxValue` elements. See [The Architecture of Scala 2.13's Collections](https://docs.scala-lang.org/overviews/core/architecture-of-scala-213-collections.html) for more info.
   * This Sink will cancel the stream after having received that many elements.
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]], [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def collection[T, That](implicit cbf: Factory[T, That with immutable.Iterable[_]]): Sink[T, Future[That]] =
    Sink.fromGraph(new SeqStage[T, That])

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
  def asPublisher[T](fanout: Boolean): Sink[T, Publisher[T]] =
    fromGraph(
      if (fanout) new FanoutPublisherSink[T](DefaultAttributes.fanoutPublisherSink, shape("FanoutPublisherSink"))
      else new PublisherSink[T](DefaultAttributes.publisherSink, shape("PublisherSink")))

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore: Sink[Any, Future[Done]] = fromGraph(GraphStages.IgnoreSink)

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreach[T](f: T ⇒ Unit): Sink[T, Future[Done]] =
    Flow[T].map(f).toMat(Sink.ignore)(Keep.right).named("foreachSink")

  /**
   * A `Sink` that will invoke the given procedure asynchronously for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] which will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def foreachAsync[T](parallelism: Int)(f: T ⇒ Future[Unit]): Sink[T, Future[Done]] =
    Flow[T].mapAsyncUnordered(parallelism)(f).toMat(Sink.ignore)(Keep.right).named("foreachAsyncSink")

  /**
   * Combine several sinks with fan-out strategy like `Broadcast` or `Balance` and returns `Sink`.
   */
  def combine[T, U](first: Sink[U, _], second: Sink[U, _], rest: Sink[U, _]*)(strategy: Int ⇒ Graph[UniformFanOutShape[T, U], NotUsed]): Sink[T, NotUsed] =

    Sink.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val d = b.add(strategy(rest.size + 2))
      d.out(0) ~> first
      d.out(1) ~> second

      @tailrec def combineRest(idx: Int, i: Iterator[Sink[U, _]]): SinkShape[T] =
        if (i.hasNext) {
          d.out(idx) ~> i.next()
          combineRest(idx + 1, i)
        } else new SinkShape(d.in)

      combineRest(2, rest.iterator)
    })

  /**
   * A `Sink` that will invoke the given function to each of the elements
   * as they pass in. The sink is materialized into a [[scala.concurrent.Future]]
   *
   * If `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Stop]] the `Future` will be completed with failure.
   *
   * If `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]] the
   * element is dropped and the stream continues.
   *
   * See also [[Flow.mapAsyncUnordered]]
   */
  @deprecated("Use `foreachAsync` instead, it allows you to choose how to run the procedure, by calling some other API returning a Future or spawning a new Future.", since = "2.5.17")
  def foreachParallel[T](parallelism: Int)(f: T ⇒ Unit)(implicit ec: ExecutionContext): Sink[T, Future[Done]] =
    Flow[T].mapAsyncUnordered(parallelism)(t ⇒ Future(f(t))).toMat(Sink.ignore)(Keep.right)

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * @see [[#foldAsync]]
   */
  def fold[U, T](zero: U)(f: (U, T) ⇒ U): Sink[T, Future[U]] =
    Flow[T].fold(zero)(f).toMat(Sink.head)(Keep.right).named("foldSink")

  /**
   * A `Sink` that will invoke the given asynchronous function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * @see [[#fold]]
   */
  def foldAsync[U, T](zero: U)(f: (U, T) ⇒ Future[U]): Sink[T, Future[U]] = Flow[T].foldAsync(zero)(f).toMat(Sink.head)(Keep.right).named("foldAsyncSink")

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
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
  def reduce[T](f: (T, T) ⇒ T): Sink[T, Future[T]] =
    Flow[T].reduce(f).toMat(Sink.head)(Keep.right).named("reduceSink")

  /**
   * A `Sink` that when the flow is completed, either through a failure or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[T](callback: Try[Done] ⇒ Unit): Sink[T, NotUsed] = {

    def newOnCompleteStage(): GraphStage[FlowShape[T, NotUsed]] = {
      new GraphStage[FlowShape[T, NotUsed]] {

        val in = Inlet[T]("in")
        val out = Outlet[NotUsed]("out")
        override val shape = FlowShape.of(in, out)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
          new GraphStageLogic(shape) with InHandler with OutHandler {

            var completionSignalled = false

            override def onPush(): Unit = pull(in)

            override def onPull(): Unit = pull(in)

            override def onUpstreamFailure(cause: Throwable): Unit = {
              callback(Failure(cause))
              completionSignalled = true
              failStage(cause)
            }

            override def onUpstreamFinish(): Unit = {
              callback(Success(Done))
              completionSignalled = true
              completeStage()
            }

            override def postStop(): Unit = {
              if (!completionSignalled) callback(Failure(new AbruptStageTerminationException(this)))
            }

            setHandlers(in, out, this)

          }
      }
    }
    Flow[T].via(newOnCompleteStage()).to(Sink.ignore).named("onCompleteSink")
  }

  /**
   * INTERNAL API
   *
   * Sends the elements of the stream to the given `ActorRef`.
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure the `onFailureMessage` will be invoked
   * and its result will be sent to the destination actor.
   *
   * It will request at most `maxInputBufferSize` number of elements from
   * upstream, but there is no back-pressure signal from the destination actor,
   * i.e. if the actor is not consuming the messages fast enough the mailbox
   * of the actor will grow. For potentially slow consumer actors it is recommended
   * to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
   * limiting operator in front of this `Sink`.
   */
  @InternalApi private[akka] def actorRef[T](ref: ActorRef, onCompleteMessage: Any, onFailureMessage: Throwable ⇒ Any): Sink[T, NotUsed] =
    fromGraph(new ActorRefSink(ref, onCompleteMessage, onFailureMessage,
      DefaultAttributes.actorRefSink, shape("ActorRefSink")))

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
   */
  def actorRef[T](ref: ActorRef, onCompleteMessage: Any): Sink[T, NotUsed] =
    fromGraph(new ActorRefSink(ref, onCompleteMessage, t ⇒ Status.Failure(t),
      DefaultAttributes.actorRefSink, shape("ActorRefSink")))

  /**
   * INTERNAL API
   *
   * Sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
   * First element is created by calling `onInitMessage` with an `ActorRef` of the actor that
   * expects acknowledgements. Then stream is waiting for acknowledgement message
   * `ackMessage` from the given actor which means that it is ready to process
   * elements. It also requires `ackMessage` message after each stream element
   * to make backpressure work.
   *
   * Every message that is sent to the actor is first transformed using `messageAdapter`.
   * This can be used to capture the ActorRef of the actor that expects acknowledgments as
   * well as transforming messages from the stream to the ones that actor under `ref` handles.
   *
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure - result of `onFailureMessage(throwable)`
   * function will be sent to the destination actor.
   */
  @InternalApi private[akka] def actorRefWithAck[T](ref: ActorRef, messageAdapter: ActorRef ⇒ T ⇒ Any,
                                                    onInitMessage: ActorRef ⇒ Any, ackMessage: Any, onCompleteMessage: Any,
                                                    onFailureMessage: (Throwable) ⇒ Any): Sink[T, NotUsed] =
    Sink.fromGraph(new ActorRefBackpressureSinkStage(ref, messageAdapter, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage))

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
   *
   */
  def actorRefWithAck[T](ref: ActorRef, onInitMessage: Any, ackMessage: Any, onCompleteMessage: Any,
                         onFailureMessage: (Throwable) ⇒ Any = Status.Failure): Sink[T, NotUsed] =
    actorRefWithAck(ref, _ ⇒ identity, _ ⇒ onInitMessage, ackMessage, onCompleteMessage, onFailureMessage)

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` must
   * be [[akka.stream.actor.ActorSubscriber]].
   *
   * @deprecated Use `akka.stream.stage.GraphStage` and `fromGraph` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.
   */
  @deprecated("Use `akka.stream.stage.GraphStage` and `fromGraph` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.", since = "2.5.0")
  def actorSubscriber[T](props: Props): Sink[T, ActorRef] = {
    require(classOf[ActorSubscriber].isAssignableFrom(props.actorClass()), "Actor must be ActorSubscriber")
    fromGraph(new ActorSubscriberSink(props, DefaultAttributes.actorSubscriberSink, shape("ActorSubscriberSink")))
  }

  /**
   * Creates a `Sink` that is materialized as an [[akka.stream.scaladsl.SinkQueue]].
   * [[akka.stream.scaladsl.SinkQueue.pull]] method is pulling element from the stream and returns ``Future[Option[T]]``.
   * `Future` completes when element is available.
   *
   * Before calling pull method second time you need to wait until previous Future completes.
   * Pull returns Failed future with ''IllegalStateException'' if previous future has not yet completed.
   *
   * `Sink` will request at most number of elements equal to size of `inputBuffer` from
   * upstream and then stop back pressure.  You can configure size of input
   * buffer by using [[Sink.withAttributes]] method.
   *
   * For stream completion you need to pull all elements from [[akka.stream.scaladsl.SinkQueue]] including last None
   * as completion marker
   *
   * See also [[akka.stream.scaladsl.SinkQueueWithCancel]]
   */
  def queue[T](): Sink[T, SinkQueueWithCancel[T]] =
    Sink.fromGraph(new QueueSink())

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
  def lazyInit[T, M](sinkFactory: T ⇒ Future[Sink[T, M]], fallback: () ⇒ M): Sink[T, Future[M]] =
    Sink.fromGraph(new LazySink[T, M](sinkFactory)).mapMaterializedValue(_.map(_.getOrElse(fallback()))(ExecutionContexts.sameThreadExecutionContext))

  /**
   * Creates a real `Sink` upon receiving the first element. Internal `Sink` will not be created if there are no elements,
   * because of completion or error.
   *
   * If upstream completes before an element was received then the `Future` is completed with `None`.
   * If upstream fails before an element was received, `sinkFactory` throws an exception, or materialization of the internal
   * sink fails then the `Future` is completed with the exception.
   * Otherwise the `Future` is completed with the materialized value of the internal sink.
   */
  def lazyInitAsync[T, M](sinkFactory: () ⇒ Future[Sink[T, M]]): Sink[T, Future[Option[M]]] =
    Sink.fromGraph(new LazySink[T, M](_ ⇒ sinkFactory()))

}
