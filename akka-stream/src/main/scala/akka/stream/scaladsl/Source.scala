/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.CompletionStage

import akka.actor.{ ActorRef, Cancellable, Props }
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.stream.actor.ActorPublisher
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.fusing.GraphStages
import akka.stream.impl.fusing.GraphStages._
import akka.stream.impl.{ PublisherSource, _ }
import akka.stream.{ Outlet, SourceShape, _ }
import akka.util.ConstantFun
import akka.{ Done, NotUsed }
import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.tailrec
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }

import akka.stream.stage.GraphStageWithMaterializedValue
import scala.compat.java8.FutureConverters._

/**
 * A `Source` is a set of stream processing steps that has one open output. It can comprise
 * any number of internal sources and transformations that are wired together, or it can be
 * an “atomic” source, e.g. from a collection or a file. Materialization turns a Source into
 * a Reactive Streams `Publisher` (at least conceptually).
 */
final class Source[+Out, +Mat](
    override val traversalBuilder: LinearTraversalBuilder,
    override val shape: SourceShape[Out])
    extends FlowOpsMat[Out, Mat]
    with Graph[SourceShape[Out], Mat] {

  override type Repr[+O] = Source[O, Mat @uncheckedVariance]
  override type ReprMat[+O, +M] = Source[O, M]

  override type Closed = RunnableGraph[Mat @uncheckedVariance]
  override type ClosedMat[+M] = RunnableGraph[M]

  override def toString: String = s"Source($shape)"

  override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] = viaMat(flow)(Keep.left)

  override def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(
      combine: (Mat, Mat2) => Mat3): Source[T, Mat3] = {
    if (flow.traversalBuilder eq Flow.identityTraversalBuilder)
      if (combine == Keep.left)
        //optimization by returning this
        this.asInstanceOf[Source[T, Mat3]] //Mat == Mat3, due to Keep.left
      else if (combine == Keep.right || combine == Keep.none) // Mat3 = NotUsed
        //optimization with LinearTraversalBuilder.empty()
        new Source[T, Mat3](
          traversalBuilder.append(LinearTraversalBuilder.empty(), flow.shape, combine),
          SourceShape(shape.out).asInstanceOf[SourceShape[T]])
      else
        new Source[T, Mat3](
          traversalBuilder.append(flow.traversalBuilder, flow.shape, combine),
          SourceShape(flow.shape.out))
    else
      new Source[T, Mat3](
        traversalBuilder.append(flow.traversalBuilder, flow.shape, combine),
        SourceShape(flow.shape.out))
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
  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Out], Mat2])(combine: (Mat, Mat2) => Mat3): RunnableGraph[Mat3] = {
    RunnableGraph(traversalBuilder.append(sink.traversalBuilder, sink.shape, combine))
  }

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  override def mapMaterializedValue[Mat2](f: Mat => Mat2): ReprMat[Out, Mat2] =
    new Source[Out, Mat2](traversalBuilder.transformMat(f.asInstanceOf[Any => Any]), shape)

  /**
   * Materializes this Source, immediately returning (1) its materialized value, and (2) a new Source
   * that can be used to consume elements from the newly materialized Source.
   */
  def preMaterialize()(implicit materializer: Materializer): (Mat, ReprMat[Out, NotUsed]) = {
    val (mat, pub) = toMat(Sink.asPublisher(fanout = true))(Keep.both).run()
    (mat, Source.fromPublisher(pub))
  }

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a [[akka.stream.scaladsl.Sink#publisher]].
   */
  def runWith[Mat2](sink: Graph[SinkShape[Out], Mat2])(implicit materializer: Materializer): Mat2 =
    toMat(sink)(Keep.right).run()

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def runFold[U](zero: U)(f: (U, Out) => U)(implicit materializer: Materializer): Future[U] =
    runWith(Sink.fold(zero)(f))

  /**
   * Shortcut for running this `Source` with a foldAsync function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def runFoldAsync[U](zero: U)(f: (U, Out) => Future[U])(implicit materializer: Materializer): Future[U] =
    runWith(Sink.foldAsync(zero)(f))

  /**
   * Shortcut for running this `Source` with a reduce function.
   * The given function is invoked for every received element, giving it its previous
   * output (from the second element) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   */
  def runReduce[U >: Out](f: (U, U) => U)(implicit materializer: Materializer): Future[U] =
    runWith(Sink.reduce(f))

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   */
  // FIXME: Out => Unit should stay, right??
  def runForeach(f: Out => Unit)(implicit materializer: Materializer): Future[Done] = runWith(Sink.foreach(f))

  /**
   * Replace the attributes of this [[Source]] with the given ones. If this Source is a composite
   * of multiple graphs, new attributes on the composite will be less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def withAttributes(attr: Attributes): Repr[Out] =
    new Source(traversalBuilder.setAttributes(attr), shape)

  /**
   * Add the given attributes to this Source. If the specific attribute was already on this source
   * it will replace the previous value. If this Source is a composite
   * of multiple graphs, the added attributes will be on the composite and therefore less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): Repr[Out] = withAttributes(traversalBuilder.attributes and attr)

  /**
   * Add a ``name`` attribute to this Source.
   */
  override def named(name: String): Repr[Out] = addAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Source`
   */
  override def async: Repr[Out] = super.async.asInstanceOf[Repr[Out]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): Repr[Out] =
    super.async(dispatcher).asInstanceOf[Repr[Out]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): Repr[Out] =
    super.async(dispatcher, inputBufferSize).asInstanceOf[Repr[Out]]

  /**
   * Converts this Scala DSL element to it's Java DSL counterpart.
   */
  def asJava: javadsl.Source[Out @uncheckedVariance, Mat @uncheckedVariance] = new javadsl.Source(this)

  /**
   * Combines several sources with fan-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  @deprecated("Use `Source.combine` on companion object instead", "2.5.5")
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(
      strategy: Int => Graph[UniformFanInShape[T, U], NotUsed]): Source[U, NotUsed] =
    Source.fromGraph(GraphDSL.create() { implicit b =>
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
   * API MAY CHANGE
   */
  @ApiMayChange
  def asSourceWithContext[Ctx](f: Out => Ctx): SourceWithContext[Out, Ctx, Mat] =
    new SourceWithContext(this.map(e => (e, f(e))))
}

object Source {

  /** INTERNAL API */
  def shape[T](name: String): SourceShape[T] = SourceShape(Outlet(name + ".out"))

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def fromPublisher[T](publisher: Publisher[T]): Source[T, NotUsed] =
    fromGraph(new PublisherSource(publisher, DefaultAttributes.publisherSource, shape("PublisherSource")))

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
  def fromIterator[T](f: () => Iterator[T]): Source[T, NotUsed] =
    apply(new immutable.Iterable[T] {
      override def iterator: Iterator[T] = f()
      override def toString: String = "() => Iterator"
    })

  /**
   * Creates [[Source]] that will continually produce given elements in specified order.
   *
   * Starts a new 'cycled' `Source` from the given elements. The producer stream of elements
   * will continue infinitely by repeating the sequence of elements provided by function parameter.
   */
  def cycle[T](f: () => Iterator[T]): Source[T, NotUsed] = {
    val iterator = Iterator.continually {
      val i = f(); if (i.isEmpty) throw new IllegalArgumentException("empty iterator") else i
    }.flatten
    fromIterator(() => iterator).withAttributes(DefaultAttributes.cycledSource)
  }

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SourceShape[T], M]): Source[T, M] = g match {
    case s: Source[T, M]                                       => s
    case s: javadsl.Source[T, M]                               => s.asScala
    case g: GraphStageWithMaterializedValue[SourceShape[T], M] =>
      // move these from the stage itself to make the returned source
      // behave as it is the stage with regards to attributes
      val attrs = g.traversalBuilder.attributes
      val noAttrStage = g.withAttributes(Attributes.none)
      new Source(
        LinearTraversalBuilder.fromBuilder(noAttrStage.traversalBuilder, noAttrStage.shape, Keep.right),
        noAttrStage.shape).withAttributes(attrs)
    case other =>
      // composite source shaped graph
      new Source(LinearTraversalBuilder.fromBuilder(other.traversalBuilder, other.shape, Keep.right), other.shape)
  }

  /**
   * Defers the creation of a [[Source]] until materialization. The `factory` function
   * exposes [[ActorMaterializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[Source]] returned by this method.
   */
  def setup[T, M](factory: (ActorMaterializer, Attributes) => Source[T, M]): Source[T, Future[M]] =
    Source.fromGraph(new SetupSourceStage(factory))

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
   * Starts a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def fromFuture[T](future: Future[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource(future))

  /**
   * Starts a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  def fromCompletionStage[T](future: CompletionStage[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource(future.toScala))

  /**
   * Streams the elements of the given future source once it successfully completes.
   * If the [[Future]] fails the stream is failed with the exception from the future. If downstream cancels before the
   * stream completes the materialized `Future` will be failed with a [[StreamDetachedException]]
   */
  def fromFutureSource[T, M](future: Future[Graph[SourceShape[T], M]]): Source[T, Future[M]] =
    fromGraph(new FutureFlattenSource(future))

  /**
   * Streams the elements of an asynchronous source once its given `completion` operator completes.
   * If the [[CompletionStage]] fails the stream is failed with the exception from the future.
   * If downstream cancels before the stream completes the materialized `Future` will be failed
   * with a [[StreamDetachedException]]
   */
  def fromSourceCompletionStage[T, M](
      completion: CompletionStage[_ <: Graph[SourceShape[T], M]]): Source[T, CompletionStage[M]] =
    fromFutureSource(completion.toScala).mapMaterializedValue(_.toJava)

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
    unfold(element)(_ => next).withAttributes(DefaultAttributes.repeat)
  }

  /**
   * Create a `Source` that will unfold a value of type `S` into
   * a pair of the next state `S` and output elements of type `E`.
   *
   * For example, all the Fibonacci numbers under 10M:
   *
   * {{{
   *   Source.unfold(0 -> 1) {
   *    case (a, _) if a > 10000000 => None
   *    case (a, b) => Some((b -> (a + b)) -> a)
   *   }
   * }}}
   */
  def unfold[S, E](s: S)(f: S => Option[(S, E)]): Source[E, NotUsed] =
    Source.fromGraph(new Unfold(s, f))

  /**
   * Same as [[unfold]], but uses an async function to generate the next state-element tuple.
   *
   * async fibonacci example:
   *
   * {{{
   *   Source.unfoldAsync(0 -> 1) {
   *    case (a, _) if a > 10000000 => Future.successful(None)
   *    case (a, b) => Future{
   *      Thread.sleep(1000)
   *      Some((b -> (a + b)) -> a)
   *    }
   *   }
   * }}}
   */
  def unfoldAsync[S, E](s: S)(f: S => Future[Option[(S, E)]]): Source[E, NotUsed] =
    Source.fromGraph(new UnfoldAsync(s, f))

  /**
   * A `Source` with no elements, i.e. an empty stream that is completed immediately for every connected `Sink`.
   */
  def empty[T]: Source[T, NotUsed] = _empty
  private[this] val _empty: Source[Nothing, NotUsed] =
    Source.fromGraph(EmptySource)

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
    Source.fromGraph(MaybeSource.asInstanceOf[Graph[SourceShape[T], Promise[Option[T]]]])

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, NotUsed] =
    Source.fromGraph(new FailedSource[T](cause))

  /**
   * Creates a `Source` that is not materialized until there is downstream demand, when the source gets materialized
   * the materialized future is completed with its value, if downstream cancels or fails without any demand the
   * create factory is never called and the materialized `Future` is failed.
   */
  def lazily[T, M](create: () => Source[T, M]): Source[T, Future[M]] =
    Source.fromGraph(new LazySource[T, M](create))

  /**
   * Creates a `Source` from supplied future factory that is not called until downstream demand. When source gets
   * materialized the materialized future is completed with the value from the factory. If downstream cancels or fails
   * without any demand the create factory is never called and the materialized `Future` is failed.
   *
   * @see [[Source.lazily]]
   */
  def lazilyAsync[T](create: () => Future[T]): Source[T, Future[NotUsed]] =
    lazily(() => fromFuture(create()))

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def asSubscriber[T]: Source[T, Subscriber[T]] =
    fromGraph(new SubscriberSource[T](DefaultAttributes.subscriberSource, shape("SubscriberSource")))

  /**
   * Creates a `Source` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` must
   * be [[akka.stream.actor.ActorPublisher]].
   *
   * @deprecated Use `akka.stream.stage.GraphStage` and `fromGraph` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.
   */
  @deprecated(
    "Use `akka.stream.stage.GraphStage` and `fromGraph` instead, it allows for all operations an Actor would and is more type-safe as well as guaranteed to be ReactiveStreams compliant.",
    since = "2.5.0")
  def actorPublisher[T](props: Props): Source[T, ActorRef] = {
    require(classOf[ActorPublisher[_]].isAssignableFrom(props.actorClass()), "Actor must be ActorPublisher")
    fromGraph(new ActorPublisherSource(props, DefaultAttributes.actorPublisherSource, shape("ActorPublisherSource")))
  }

  /**
   * INTERNAL API
   *
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
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped if there is no demand
   * from downstream. When `bufferSize` is 0 the `overflowStrategy` does not matter. An async boundary is added after
   * this Source; as such, it is never safe to assume the downstream will always generate demand.
   *
   * The stream can be completed successfully by sending the actor reference a message that is matched by
   * `completionMatcher` in which case already buffered elements will be signaled before signaling
   * completion.
   *
   * The stream can be completed with failure by sending a message that is matched by `failureMatcher`. The extracted
   * [[Throwable]] will be used to fail the stream. In case the Actor is still draining its internal buffer (after having received
   * a message matched by `completionMatcher`) before signaling completion and it receives a message matched by `failureMatcher`,
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * Note that terminating the actor without first completing it, either with a success or a
   * failure, will prevent the actor triggering downstream completion and the stream will continue
   * to run even though the source actor is dead. Therefore you should **not** attempt to
   * manually terminate the actor such as with a [[akka.actor.PoisonPill]].
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * See also [[akka.stream.scaladsl.Source.queue]].
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @InternalApi private[akka] def actorRef[T](
      completionMatcher: PartialFunction[Any, CompletionStrategy],
      failureMatcher: PartialFunction[Any, Throwable],
      bufferSize: Int,
      overflowStrategy: OverflowStrategy): Source[T, ActorRef] = {
    require(bufferSize >= 0, "bufferSize must be greater than or equal to 0")
    require(!overflowStrategy.isBackpressure, "Backpressure overflowStrategy not supported")
    Source
      .fromGraph(new ActorRefSource(bufferSize, overflowStrategy, completionMatcher, failureMatcher))
      .withAttributes(DefaultAttributes.actorRefSource)
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
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped if there is no demand
   * from downstream. When `bufferSize` is 0 the `overflowStrategy` does not matter. An async boundary is added after
   * this Source; as such, it is never safe to assume the downstream will always generate demand.
   *
   * The stream can be completed successfully by sending the actor reference a [[akka.actor.Status.Success]].
   * If the content is [[akka.stream.CompletionStrategy.immediately]] the completion will be signaled immediately,
   * otherwise if the content is [[akka.stream.CompletionStrategy.draining]] (or anything else)
   * already buffered elements will be signaled before signaling completion.
   * Sending [[akka.actor.PoisonPill]] will signal completion immediately but this behavior is deprecated and scheduled to be removed.
   *
   * The stream can be completed with failure by sending a [[akka.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * a [[akka.actor.Status.Success]]) before signaling completion and it receives a [[akka.actor.Status.Failure]],
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * See also [[akka.stream.scaladsl.Source.queue]].
   *
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, ActorRef] =
    actorRef({
      case akka.actor.Status.Success(s: CompletionStrategy) => s
      case akka.actor.Status.Success(_)                     => CompletionStrategy.Draining
      case akka.actor.Status.Success                        => CompletionStrategy.Draining
    }, { case akka.actor.Status.Failure(cause)              => cause }, bufferSize, overflowStrategy)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def actorRefWithAck[T](
      ackTo: Option[ActorRef],
      ackMessage: Any,
      completionMatcher: PartialFunction[Any, CompletionStrategy],
      failureMatcher: PartialFunction[Any, Throwable]): Source[T, ActorRef] = {
    Source.fromGraph(new ActorRefBackpressureSource(ackTo, ackMessage, completionMatcher, failureMatcher))
  }

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * and a new message will only be accepted after the previous messages has been consumed and acknowledged back.
   * The stream will complete with failure if a message is sent before the acknowledgement has been replied back.
   *
   * The stream can be completed successfully by sending the actor reference a [[akka.actor.Status.Success]].
   * If the content is [[akka.stream.CompletionStrategy.immediately]] the completion will be signaled immidiately,
   * otherwise if the content is [[akka.stream.CompletionStrategy.draining]] (or anything else)
   * already buffered element will be signaled before siganling completion.
   *
   * The stream can be completed with failure by sending a [[akka.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * a [[akka.actor.Status.Success]]) before signaling completion and it receives a [[akka.actor.Status.Failure]],
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   */
  def actorRefWithAck[T](ackMessage: Any): Source[T, ActorRef] =
    actorRefWithAck(None, ackMessage, {
      case akka.actor.Status.Success(s: CompletionStrategy) => s
      case akka.actor.Status.Success(_)                     => CompletionStrategy.Draining
      case akka.actor.Status.Success                        => CompletionStrategy.Draining
    }, { case akka.actor.Status.Failure(cause)              => cause })

  /**
   * Combines several sources with fan-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(
      strategy: Int => Graph[UniformFanInShape[T, U], NotUsed]): Source[U, NotUsed] =
    Source.fromGraph(GraphDSL.create() { implicit b =>
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
   * Combines two sources with fan-in strategy like `Merge` or `Concat` and returns `Source` with a materialized value.
   */
  def combineMat[T, U, M1, M2, M](first: Source[T, M1], second: Source[T, M2])(
      strategy: Int => Graph[UniformFanInShape[T, U], NotUsed])(matF: (M1, M2) => M): Source[U, M] = {
    val secondPartiallyCombined = GraphDSL.create(second) { implicit b => secondShape =>
      import GraphDSL.Implicits._
      val c = b.add(strategy(2))
      secondShape ~> c.in(1)
      FlowShape(c.in(0), c.out)
    }
    first.viaMat(secondPartiallyCombined)(matF)
  }

  /**
   * Combine the elements of multiple streams into a stream of sequences.
   */
  def zipN[T](sources: immutable.Seq[Source[T, _]]): Source[immutable.Seq[T], NotUsed] =
    zipWithN(ConstantFun.scalaIdentityFunction[immutable.Seq[T]])(sources).addAttributes(DefaultAttributes.zipN)

  /*
   * Combine the elements of multiple streams into a stream of sequences using a combiner function.
   */
  def zipWithN[T, O](zipper: immutable.Seq[T] => O)(sources: immutable.Seq[Source[T, _]]): Source[O, NotUsed] = {
    val source = sources match {
      case immutable.Seq()       => empty[O]
      case immutable.Seq(source) => source.map(t => zipper(immutable.Seq(t))).mapMaterializedValue(_ => NotUsed)
      case s1 +: s2 +: ss        => combine(s1, s2, ss: _*)(ZipWithN(zipper))
    }

    source.addAttributes(DefaultAttributes.zipWithN)
  }

  /**
   * Creates a `Source` that is materialized as an [[akka.stream.scaladsl.SourceQueueWithComplete]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received. Elements in the buffer will be discarded
   * if downstream is terminated.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[akka.stream.scaladsl.SourceQueueWithComplete.offer]] returns `Future[QueueOfferResult]` which completes with
   * `QueueOfferResult.Enqueued` if element was added to buffer or sent downstream. It completes with
   * `QueueOfferResult.Dropped` if element was dropped. Can also complete  with `QueueOfferResult.Failure` -
   * when stream failed or `QueueOfferResult.QueueClosed` when downstream is completed.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] will not complete last `offer():Future`
   * call when buffer is full.
   *
   * You can watch accessibility of stream with [[akka.stream.scaladsl.SourceQueueWithComplete.watchCompletion]].
   * It returns future that completes with success when the operator is completed or fails when the stream is failed.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received message will wait
   * for downstream demand unless there is another message waiting for downstream demand, in that case
   * offer result will be completed according to the overflow strategy.
   *
   * @param bufferSize size of buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def queue[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, SourceQueueWithComplete[T]] =
    Source.fromGraph(new QueueSource(bufferSize, overflowStrategy).withAttributes(DefaultAttributes.queueSource))

  /**
   * Start a new `Source` from some resource which can be opened, read and closed.
   * Interaction with resource happens in a blocking way.
   *
   * Example:
   * {{{
   * Source.unfoldResource(
   *   () => new BufferedReader(new FileReader("...")),
   *   reader => Option(reader.readLine()),
   *   reader => reader.close())
   * }}}
   *
   * You can use the supervision strategy to handle exceptions for `read` function. All exceptions thrown by `create`
   * or `close` will fail the stream.
   *
   * `Restart` supervision strategy will close and create blocking IO again. Default strategy is `Stop` which means
   * that stream will be terminated on error in `read` function by default.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.materializer.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * @param create - function that is called on stream start and creates/opens resource.
   * @param read - function that reads data from opened resource. It is called each time backpressure signal
   *             is received. Stream calls close and completes when `read` returns None.
   * @param close - function that closes resource
   */
  def unfoldResource[T, S](create: () => S, read: (S) => Option[T], close: (S) => Unit): Source[T, NotUsed] =
    Source.fromGraph(new UnfoldResourceSource(create, read, close))

  /**
   * Start a new `Source` from some resource which can be opened, read and closed.
   * It's similar to `unfoldResource` but takes functions that return `Futures` instead of plain values.
   *
   * You can use the supervision strategy to handle exceptions for `read` function or failures of produced `Futures`.
   * All exceptions thrown by `create` or `close` as well as fails of returned futures will fail the stream.
   *
   * `Restart` supervision strategy will close and create resource. Default strategy is `Stop` which means
   * that stream will be terminated on error in `read` function (or future) by default.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.materializer.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * @param create - function that is called on stream start and creates/opens resource.
   * @param read - function that reads data from opened resource. It is called each time backpressure signal
   *             is received. Stream calls close and completes when `Future` from read function returns None.
   * @param close - function that closes resource
   */
  def unfoldResourceAsync[T, S](
      create: () => Future[S],
      read: (S) => Future[Option[T]],
      close: (S) => Future[Done]): Source[T, NotUsed] =
    Source.fromGraph(new UnfoldResourceSourceAsync(create, read, close))

}
