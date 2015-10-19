/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ ActorRef, Cancellable, Props }
import akka.stream.impl.Stages.{ DefaultAttributes, MaterializingStageFactory, StageModule }
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.GraphStages.TickSource
import akka.stream.impl.{ EmptyPublisher, ErrorPublisher, _ }
import akka.stream.{ Outlet, SourceShape, _ }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ Future, Promise }
import scala.language.higherKinds

/**
 * A `Source` is a set of stream processing steps that has one open output. It can comprise
 * any number of internal sources and transformations that are wired together, or it can be
 * an “atomic” source, e.g. from a collection or a file. Materialization turns a Source into
 * a Reactive Streams `Publisher` (at least conceptually).
 */
final class Source[+Out, +Mat](private[stream] override val module: Module)
  extends FlowOps[Out, Mat] with Graph[SourceShape[Out], Mat] {

  override type Repr[+O, +M] = Source[O, M]

  override val shape: SourceShape[Out] = module.shape.asInstanceOf[SourceShape[Out]]

  def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Source[T, Mat3] = {
    if (flow.module.isInstanceOf[Stages.Identity]) this.asInstanceOf[Source[T, Mat3]]
    else {
      val flowCopy = flow.module.carbonCopy
      new Source(
        module
          .fuse(flowCopy, shape.outlet, flowCopy.shape.inlets.head, combine)
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
    RunnableGraph(module.fuse(sinkCopy, shape.outlet, sinkCopy.shape.inlets.head, combine))
  }

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): Repr[Out, Mat2] =
    new Source(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /** INTERNAL API */
  override private[scaladsl] def andThen[U](op: StageModule): Repr[U, Mat] = {
    // No need to copy here, op is a fresh instance
    new Source(
      module
        .fuse(op, shape.outlet, op.inPort)
        .replaceShape(SourceShape(op.outPort)))
  }

  override private[scaladsl] def andThenMat[U, Mat2](op: MaterializingStageFactory): Repr[U, Mat2] = {
    new Source(
      module
        .fuse(op, shape.outlet, op.inPort, Keep.right)
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
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  def runForeach(f: Out ⇒ Unit)(implicit materializer: Materializer): Future[Unit] = runWith(Sink.foreach(f))

  /**
   * Nests the current Source and returns a Source with the given Attributes
   * @param attr the attributes to add
   * @return a new Source with the added attributes
   */
  override def withAttributes(attr: Attributes): Repr[Out, Mat] =
    new Source(module.withAttributes(attr).nest()) // User API

  override def named(name: String): Repr[Out, Mat] = withAttributes(Attributes.name(name))

  /** Converts this Scala DSL element to it's Java DSL counterpart. */
  def asJava: javadsl.Source[Out, Mat] = new javadsl.Source(this)

  /**
   * Combines several sources with fun-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(strategy: Int ⇒ Graph[UniformFanInShape[T, U], Unit]): Source[U, Unit] =
    Source.wrap(FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._
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

object Source extends SourceApply {

  private[this] final val _id: Any ⇒ Any = x ⇒ x
  private[this] final def id[A]: A ⇒ A = _id.asInstanceOf[A ⇒ A]

  private[stream] def apply[Out, Mat](module: SourceModule[Out, Mat]): Source[Out, Mat] =
    new Source(module)

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
  def apply[T](publisher: Publisher[T]): Source[T, Unit] =
    new Source(new PublisherSource(publisher, DefaultAttributes.publisherSource, shape("PublisherSource")))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source(() => Iterator.from(0))`
   *
   * Start a new `Source` from the given function that produces anIterator.
   * The produced stream of elements will continue until the iterator runs empty
   * or fails during evaluation of the `next()` method.
   * Elements are pulled out of the iterator in accordance with the demand coming
   * from the downstream transformation steps.
   */
  def apply[T](f: () ⇒ Iterator[T]): Source[T, Unit] =
    apply(new immutable.Iterable[T] {
      override def iterator: Iterator[T] = f()
      override def toString: String = "() => Iterator"
    })

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  def wrap[T, M](g: Graph[SourceShape[T], M]): Source[T, M] = g match {
    case s: Source[T, M] ⇒ s
    case other           ⇒ new Source(other.module)
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
  def apply[T](iterable: immutable.Iterable[T]): Source[T, Unit] =
    Source.single(iterable).mapConcat(id).withAttributes(DefaultAttributes.iterableSource)

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): Source[T, Unit] =
    new Source(
      new PublisherSource(
        SingleElementPublisher(future, "FutureSource"),
        DefaultAttributes.futureSource,
        shape("FutureSource"))).mapAsyncUnordered(1)(id)

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: T): Source[T, Cancellable] =
    wrap(new TickSource[T](initialDelay, interval, tick))

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def single[T](element: T): Source[T, Unit] =
    new Source(
      new PublisherSource(
        SingleElementPublisher(element, "SingleSource"),
        DefaultAttributes.singleSource,
        shape("SingleSource")))

  /**
   * Create a `Source` that will continually emit the given element.
   */
  def repeat[T](element: T): Source[T, Unit] = {
    ReactiveStreamsCompliance.requireNonNullElement(element)
    new Source(
      new PublisherSource(
        SingleElementPublisher(
          new immutable.Iterable[T] {
            override val iterator: Iterator[T] = Iterator.continually(element)

            override def toString: String = "repeat(" + element + ")"
          }, "RepeatSource"),
        DefaultAttributes.repeat,
        shape("RepeatSource"))).mapConcat(id)
  }

  /**
   * A `Source` with no elements, i.e. an empty stream that is completed immediately for every connected `Sink`.
   */
  def empty[T]: Source[T, Unit] = _empty
  private[this] val _empty: Source[Nothing, Unit] =
    new Source(
      new PublisherSource[Nothing](
        EmptyPublisher,
        DefaultAttributes.emptySource,
        shape("EmptySource")))

  /**
   * Create a `Source` with no elements, which does not complete its downstream,
   * until externally triggered to do so.
   *
   * It materializes a [[scala.concurrent.Promise]] which will be completed
   * when the downstream stage of this source cancels. This promise can also
   * be used to externally trigger completion, which the source then signals
   * to its downstream.
   */
  def lazyEmpty[T]: Source[T, Promise[Unit]] =
    new Source(new LazyEmptySource[T](DefaultAttributes.lazyEmptySource, shape("LazyEmptySource")))

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, Unit] =
    new Source(
      new PublisherSource(
        ErrorPublisher(cause, "FailedSource")[T],
        DefaultAttributes.failedSource,
        shape("FailedSource")))

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def subscriber[T]: Source[T, Subscriber[T]] =
    new Source(new SubscriberSource[T](DefaultAttributes.subscriberSource, shape("SubscriberSource")))

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorPublisher]].
   */
  def actorPublisher[T](props: Props): Source[T, ActorRef] =
    new Source(new ActorPublisherSource(props, DefaultAttributes.actorPublisherSource, shape("ActorPublisherSource")))

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
    require(overflowStrategy != OverflowStrategy.Backpressure, "Backpressure overflowStrategy not supported")
    new Source(new ActorRefSource(bufferSize, overflowStrategy, DefaultAttributes.actorRefSource, shape("ActorRefSource")))
  }

  /**
   * Combines several sources with fun-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(strategy: Int ⇒ Graph[UniformFanInShape[T, U], Unit]): Source[U, Unit] =
    Source.wrap(FlowGraph.partial() { implicit b ⇒
      import FlowGraph.Implicits._
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
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[akka.stream.SourceQueue.offer]] returns ``Future[Boolean]`` which completes with true
   * if element was added to buffer or sent downstream. It completes
   * with false if element was dropped.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] will not complete `offer():Future` until buffer is full.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped
   * if there is no demand from downstream. When `bufferSize` is 0 the `overflowStrategy` does
   * not matter.
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   * @param timeout Timeout for ``SourceQueue.offer(T):Future[Boolean]``
   */
  def queue[T](bufferSize: Int, overflowStrategy: OverflowStrategy, timeout: FiniteDuration = 5.seconds): Source[T, SourceQueue[T]] = {
    require(bufferSize >= 0, "bufferSize must be greater than or equal to 0")
    new Source(new AcknowledgeSource(bufferSize, overflowStrategy, DefaultAttributes.acknowledgeSource, shape("AcknowledgeSource")))
  }

}
