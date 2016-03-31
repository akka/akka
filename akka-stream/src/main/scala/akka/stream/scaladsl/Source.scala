/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.{ Done, NotUsed }
import akka.actor.{ ActorRef, Cancellable, Props }
import akka.stream.actor.ActorPublisher
import akka.stream.impl.Stages.{ DefaultAttributes, StageModule }
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.GraphStages
import akka.stream.impl.fusing.GraphStages._
import akka.stream.impl.{ EmptyPublisher, ErrorPublisher, _ }
import akka.stream.{ Outlet, SourceShape, _ }
import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.duration.{ FiniteDuration }
import scala.concurrent.{ Future, Promise }
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

/**
 * A `Source` is a set of stream processing steps that has one open output. It can comprise
 * any number of internal sources and transformations that are wired together, or it can be
 * an “atomic” source, e.g. from a collection or a file. Materialization turns a Source into
 * a Reactive Streams `Publisher` (at least conceptually).
 */
final class Source[+Out, +Mat](private[stream] override val module: Module)
  extends FlowOpsMat[Out, Mat] with Graph[SourceShape[Out], Mat] {

  override type Repr[+O] = Source[O, Mat @uncheckedVariance]
  override type ReprMat[+O, +M] = Source[O, M]

  override type Closed = RunnableGraph[Mat @uncheckedVariance]
  override type ClosedMat[+M] = RunnableGraph[M]

  override val shape: SourceShape[Out] = module.shape.asInstanceOf[SourceShape[Out]]

  override def toString: String = s"Source($shape, $module)"

  override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] = viaMat(flow)(Keep.left)

  override def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Source[T, Mat3] = {
    if (flow.module eq GraphStages.Identity.module) this.asInstanceOf[Source[T, Mat3]]
    else {
      val flowCopy = flow.module.carbonCopy
      new Source(
        module
          .fuse(flowCopy, shape.out, flowCopy.shape.inlets.head, combine)
          .replaceShape(SourceShape(flowCopy.shape.outlets.head)))
    }
  }

  /**
   * Connect this [[akka.stream.scaladsl.Source]] to a [[akka.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def to[Mat2](sink: Graph[SinkShape[Out], Mat2]): RunnableGraph[Mat] = toMat(sink)(Keep.left)

  /**
   * Connect this [[akka.stream.scaladsl.Source]] to a [[akka.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Out], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): RunnableGraph[Mat3] = {
    val sinkCopy = sink.module.carbonCopy
    RunnableGraph(module.fuse(sinkCopy, shape.out, sinkCopy.shape.inlets.head, combine))
  }

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  override def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): ReprMat[Out, Mat2] =
    new Source[Out, Mat2](module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /** INTERNAL API */
  override private[scaladsl] def deprecatedAndThen[U](op: StageModule): Repr[U] = {
    // No need to copy here, op is a fresh instance
    new Source(
      module
        .fuse(op, shape.out, op.inPort)
        .replaceShape(SourceShape(op.outPort)))
  }

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a [[akka.stream.scaladsl.Sink#publisher]].
   */
  def runWith[Mat2](sink: Graph[SinkShape[Out], Mat2])(implicit materializer: Materializer): Mat2 = toMat(sink)(Keep.right).run()

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def runFold[U](zero: U)(f: (U, Out) ⇒ U)(implicit materializer: Materializer): Future[U] =
    runWith(Sink.fold(zero)(f))

  /**
   * Shortcut for running this `Source` with a reduce function.
   * The given function is invoked for every received element, giving it its previous
   * output (from the second element) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def runReduce[U >: Out](f: (U, U) ⇒ U)(implicit materializer: Materializer): Future[U] =
    runWith(Sink.reduce(f))

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  // FIXME: Out => Unit should stay, right??
  def runForeach(f: Out ⇒ Unit)(implicit materializer: Materializer): Future[Done] = runWith(Sink.foreach(f))

  /**
   * Change the attributes of this [[Source]] to the given ones and seal the list
   * of attributes. This means that further calls will not be able to remove these
   * attributes, but instead add new ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def withAttributes(attr: Attributes): Repr[Out] =
    new Source(module.withAttributes(attr))

  /**
   * Add the given attributes to this Source. Further calls to `withAttributes`
   * will not remove these attributes. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def addAttributes(attr: Attributes): Repr[Out] = withAttributes(module.attributes and attr)

  /**
   * Add a ``name`` attribute to this Flow.
   */
  override def named(name: String): Repr[Out] = addAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Source`
   */
  override def async: Repr[Out] = addAttributes(Attributes.asyncBoundary)

  /** Converts this Scala DSL element to it's Java DSL counterpart. */
  def asJava: javadsl.Source[Out, Mat] = new javadsl.Source(this)

  /**
   * Combines several sources with fun-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(strategy: Int ⇒ Graph[UniformFanInShape[T, U], NotUsed]): Source[U, NotUsed] =
    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val c = b.add(strategy(rest.size + 2))
      first ~> c.in(0)
      second ~> c.in(1)

      @tailrec def combineRest(idx: Int, i: Iterator[Source[T, _]]): SourceShape[U] =
        if (i.hasNext) {
          i.next() ~> c.in(idx)
          combineRest(idx + 1, i)
        } else SourceShape(c.out)

      combineRest(2, rest.iterator)
    })
}

object Source {
  /** INTERNAL API */
  private[stream] def shape[T](name: String): SourceShape[T] = SourceShape(Outlet(name + ".out"))

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def fromPublisher[T](publisher: Publisher[T]): Source[T, NotUsed] =
    new Source(new PublisherSource(publisher, DefaultAttributes.publisherSource, shape("PublisherSource")))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source.fromIterator(() => Iterator.from(0))`
   *
   * Start a new `Source` from the given function that produces anIterator.
   * The produced stream of elements will continue until the iterator runs empty
   * or fails during evaluation of the `next()` method.
   * Elements are pulled out of the iterator in accordance with the demand coming
   * from the downstream transformation steps.
   */
  def fromIterator[T](f: () ⇒ Iterator[T]): Source[T, NotUsed] =
    apply(new immutable.Iterable[T] {
      override def iterator: Iterator[T] = f()
      override def toString: String = "() => Iterator"
    })

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SourceShape[T], M]): Source[T, M] = g match {
    case s: Source[T, M]         ⇒ s
    case s: javadsl.Source[T, M] ⇒ s.asScala
    case other                   ⇒ new Source(other.module)
  }

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage: `Source(Seq(1,2,3))`
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Source[T, NotUsed] =
    single(iterable).mapConcat(ConstantFun.scalaIdentityFunction).withAttributes(DefaultAttributes.iterableSource)

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def fromFuture[T](future: Future[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource(future))

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def fromCompletionStage[T](future: CompletionStage[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource(future.toScala))

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def tick[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: T): Source[T, Cancellable] =
    fromGraph(new TickSource[T](initialDelay, interval, tick))

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def single[T](element: T): Source[T, NotUsed] =
    fromGraph(new GraphStages.SingleSource(element))

  /**
   * Create a `Source` that will continually emit the given element.
   */
  def repeat[T](element: T): Source[T, NotUsed] = {
    val next = Some((element, element))
    unfold(element)(_ ⇒ next).withAttributes(DefaultAttributes.repeat)
  }

  /**
   * Create a `Source` that will unfold a value of type `S` into
   * a pair of the next state `S` and output elements of type `E`.
   *
   * For example, all the Fibonacci numbers under 10M:
   *
   * {{{
   *   Source.unfold(0 → 1) {
   *    case (a, _) if a > 10000000 ⇒ None
   *    case (a, b) ⇒ Some((b → (a + b)) → a)
   *   }
   * }}}
   */
  def unfold[S, E](s: S)(f: S ⇒ Option[(S, E)]): Source[E, NotUsed] =
    Source.fromGraph(new Unfold(s, f))

  /**
   * Same as [[unfold]], but uses an async function to generate the next state-element tuple.
   *
   * async fibonacci example:
   *
   * {{{
   *   Source.unfoldAsync(0 → 1) {
   *    case (a, _) if a > 10000000 ⇒ Future.successful(None)
   *    case (a, b) ⇒ Future{
   *      Thread.sleep(1000)
   *      Some((b → (a + b)) → a)
   *    }
   *   }
   * }}}
   */
  def unfoldAsync[S, E](s: S)(f: S ⇒ Future[Option[(S, E)]]): Source[E, NotUsed] =
    Source.fromGraph(new UnfoldAsync(s, f))

  /**
   * A `Source` with no elements, i.e. an empty stream that is completed immediately for every connected `Sink`.
   */
  def empty[T]: Source[T, NotUsed] = _empty
  private[this] val _empty: Source[Nothing, NotUsed] =
    new Source(
      new PublisherSource[Nothing](
        EmptyPublisher,
        DefaultAttributes.emptySource,
        shape("EmptySource")))

  /**
   * Create a `Source` which materializes a [[scala.concurrent.Promise]] which controls what element
   * will be emitted by the Source.
   * If the materialized promise is completed with a Some, that value will be produced downstream,
   * followed by completion.
   * If the materialized promise is completed with a None, no value will be produced downstream and completion will
   * be signalled immediately.
   * If the materialized promise is completed with a failure, then the returned source will terminate with that error.
   * If the downstream of this source cancels before the promise has been completed, then the promise will be completed
   * with None.
   */
  def maybe[T]: Source[T, Promise[Option[T]]] =
    new Source(new MaybeSource[T](DefaultAttributes.maybeSource, shape("MaybeSource")))

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, NotUsed] =
    new Source(
      new PublisherSource(
        ErrorPublisher(cause, "FailedSource")[T],
        DefaultAttributes.failedSource,
        shape("FailedSource")))

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def asSubscriber[T]: Source[T, Subscriber[T]] =
    new Source(new SubscriberSource[T](DefaultAttributes.subscriberSource, shape("SubscriberSource")))

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` must
   * be [[akka.stream.actor.ActorPublisher]].
   */
  def actorPublisher[T](props: Props): Source[T, ActorRef] = {
    require(classOf[ActorPublisher[_]].isAssignableFrom(props.actorClass()), "Actor must be ActorPublisher")
    new Source(new ActorPublisherSource(props, DefaultAttributes.actorPublisherSource, shape("ActorPublisherSource")))
  }

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] is not supported, and an
   * IllegalArgument("Backpressure overflowStrategy not supported") will be thrown if it is passed as argument.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped
   * if there is no demand from downstream. When `bufferSize` is 0 the `overflowStrategy` does
   * not matter.
   *
   * The stream can be completed successfully by sending the actor reference an [[akka.actor.Status.Success]]
   * message in which case already buffered elements will be signaled before signaling completion,
   * or by sending a [[akka.actor.PoisonPill]] in which case completion will be signaled immediately.
   *
   * The stream can be completed with failure by sending [[akka.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * an [[akka.actor.Status.Success]]) before signaling completion and it receives a [[akka.actor.Status.Failure]],
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, ActorRef] = {
    require(bufferSize >= 0, "bufferSize must be greater than or equal to 0")
    require(overflowStrategy != OverflowStrategies.Backpressure, "Backpressure overflowStrategy not supported")
    new Source(new ActorRefSource(bufferSize, overflowStrategy, DefaultAttributes.actorRefSource, shape("ActorRefSource")))
  }

  /**
   * Combines several sources with fun-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(strategy: Int ⇒ Graph[UniformFanInShape[T, U], NotUsed]): Source[U, NotUsed] =
    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val c = b.add(strategy(rest.size + 2))
      first ~> c.in(0)
      second ~> c.in(1)

      @tailrec def combineRest(idx: Int, i: Iterator[Source[T, _]]): SourceShape[U] =
        if (i.hasNext) {
          i.next() ~> c.in(idx)
          combineRest(idx + 1, i)
        } else SourceShape(c.out)

      combineRest(2, rest.iterator)
    })

  /**
   * Creates a `Source` that is materialized as an [[akka.stream.SourceQueue]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received. Elements in the buffer will be discarded
   * if downstream is terminated.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[akka.stream.SourceQueue.offer]] returns ``Future[StreamCallbackStatus[Boolean]]`` which completes with `Success(true)`
   * if element was added to buffer or sent downstream. It completes with `Success(false)` if element was dropped. Can also complete
   * with [[akka.stream.StreamCallbackStatus.Failure]] - when stream failed or [[akka.stream.StreamCallbackStatus.StreamCompleted]]
   * when downstream is completed.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] will not complete last `offer():Future`
   * call when buffer is full.
   *
   * You can watch accessibility of stream with [[akka.stream.SourceQueue.watchCompletion]].
   * It returns future that completes with success when stream is completed or fail when stream is failed.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received message will wait for downstream demand.
   * When `bufferSize` is 0 the `overflowStrategy` does not matter.
   *
   * SourceQueue that current source is materialized to is for single thread usage only.
   *
   * @param bufferSize size of buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def queue[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, SourceQueueWithComplete[T]] =
    Source.fromGraph(new QueueSource(bufferSize, overflowStrategy).withAttributes(DefaultAttributes.queueSource))

}
