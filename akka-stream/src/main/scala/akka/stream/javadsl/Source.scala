/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util
import java.util.Optional
import java.util.concurrent.{ CompletableFuture, CompletionStage }
import java.util.function.{ BiFunction, Supplier }

import akka.actor.{ ActorRef, Cancellable, ClassicActorSystemProvider }
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.japi.function.Creator
import akka.japi.{ function, JavaPartialFunction, Pair, Util }
import akka.stream._
import akka.stream.impl.LinearTraversalBuilder
import akka.util.JavaDurationConverters._
import akka.util.ccompat.JavaConverters._
import akka.util.{ unused, _ }
import akka.{ Done, NotUsed }
import com.github.ghik.silencer.silent
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, Promise }
import scala.reflect.ClassTag

/** Java API */
object Source {
  private[this] val _empty = new Source[Any, NotUsed](scaladsl.Source.empty)

  /**
   * Create a `Source` with no elements, i.e. an empty stream that is completed immediately
   * for every connected `Sink`.
   */
  def empty[O](): Source[O, NotUsed] = _empty.asInstanceOf[Source[O, NotUsed]]

  /**
   * Create a `Source` with no elements. The result is the same as calling `Source.<O>empty()`
   */
  def empty[T](@unused clazz: Class[T]): Source[T, NotUsed] = empty[T]()

  /**
   * Create a `Source` which materializes a [[java.util.concurrent.CompletableFuture]] which controls what element
   * will be emitted by the Source.
   * If the materialized promise is completed with a filled Optional, that value will be produced downstream,
   * followed by completion.
   * If the materialized promise is completed with an empty Optional, no value will be produced downstream and completion will
   * be signalled immediately.
   * If the materialized promise is completed with a failure, then the source will fail with that error.
   * If the downstream of this source cancels or fails before the promise has been completed, then the promise will be completed
   * with an empty Optional.
   */
  def maybe[T]: Source[T, CompletableFuture[Optional[T]]] = {
    new Source(scaladsl.Source.maybe[T].mapMaterializedValue { scalaOptionPromise: Promise[Option[T]] =>
      val javaOptionPromise = new CompletableFuture[Optional[T]]()
      scalaOptionPromise.completeWith(
        javaOptionPromise.toScala.map(_.asScala)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext))

      javaOptionPromise
    })
  }

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def fromPublisher[O](publisher: Publisher[O]): javadsl.Source[O, NotUsed] =
    new Source(scaladsl.Source.fromPublisher(publisher))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage:
   *
   * {{{
   * List<Integer> data = new ArrayList<Integer>();
   * data.add(1);
   * data.add(2);
   * data.add(3);
   * Source.from(() -> data.iterator());
   * }}}
   *
   * Start a new `Source` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def fromIterator[O](f: function.Creator[java.util.Iterator[O]]): javadsl.Source[O, NotUsed] =
    new Source(scaladsl.Source.fromIterator(() => f.create().asScala))

  /**
   * Helper to create 'cycled' [[Source]] from iterator provider.
   * Example usage:
   *
   * {{{
   * Source.cycle(() -> Arrays.asList(1, 2, 3).iterator());
   * }}}
   *
   * Start a new 'cycled' `Source` from the given elements. The producer stream of elements
   * will continue infinitely by repeating the sequence of elements provided by function parameter.
   */
  def cycle[O](f: function.Creator[java.util.Iterator[O]]): javadsl.Source[O, NotUsed] =
    new Source(scaladsl.Source.cycle(() => f.create().asScala))

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage:
   * {{{
   * List<Integer> data = new ArrayList<Integer>();
   * data.add(1);
   * data.add(2);
   * data.add(3);
   * Source.from(data);
   * }}}
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as a `Source`. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   */
  def from[O](iterable: java.lang.Iterable[O]): javadsl.Source[O, NotUsed] = {
    // this adapter is not immutable if the underlying java.lang.Iterable is modified
    // but there is not anything we can do to prevent that from happening.
    // ConcurrentModificationException will be thrown in some cases.
    val scalaIterable = new immutable.Iterable[O] {
      override def iterator: Iterator[O] = iterable.iterator().asScala
    }
    new Source(scaladsl.Source(scalaIterable))
  }

  /**
   * Creates [[Source]] that represents integer values in range ''[start;end]'', step equals to 1.
   * It allows to create `Source` out of range as simply as on Scala `Source(1 to N)`
   *
   * Uses [[scala.collection.immutable.Range.inclusive(Int, Int)]] internally
   *
   * @see [[scala.collection.immutable.Range.inclusive(Int, Int)]]
   */
  def range(start: Int, end: Int): javadsl.Source[Integer, NotUsed] = range(start, end, 1)

  /**
   * Creates [[Source]] that represents integer values in range ''[start;end]'', with the given step.
   * It allows to create `Source` out of range as simply as on Scala `Source(1 to N)`
   *
   * Uses [[scala.collection.immutable.Range.inclusive(Int, Int, Int)]] internally
   *
   * @see [[scala.collection.immutable.Range.inclusive(Int, Int, Int)]]
   */
  def range(start: Int, end: Int, step: Int): javadsl.Source[Integer, NotUsed] =
    fromIterator[Integer](new function.Creator[util.Iterator[Integer]]() {
      def create(): util.Iterator[Integer] =
        Range.inclusive(start, end, step).iterator.asJava.asInstanceOf[util.Iterator[Integer]]
    })

  /**
   * Start a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  @deprecated("Use 'Source.future' instead", "2.6.0")
  def fromFuture[O](future: Future[O]): javadsl.Source[O, NotUsed] =
    new Source(scaladsl.Source.future(future))

  /**
   * Starts a new `Source` from the given `CompletionStage`. The stream will consist of
   * one element when the `CompletionStage` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `CompletionStage` is completed with a failure.
   */
  @deprecated("Use 'Source.completionStage' instead", "2.6.0")
  def fromCompletionStage[O](future: CompletionStage[O]): javadsl.Source[O, NotUsed] =
    new Source(scaladsl.Source.completionStage(future))

  /**
   * Streams the elements of the given future source once it successfully completes.
   * If the [[Future]] fails the stream is failed with the exception from the future. If downstream cancels before the
   * stream completes the materialized [[Future]] will be failed with a [[StreamDetachedException]].
   */
  @deprecated("Use 'Source.futureSource' (potentially together with `Source.fromGraph`) instead", "2.6.0")
  def fromFutureSource[T, M](future: Future[_ <: Graph[SourceShape[T], M]]): javadsl.Source[T, Future[M]] =
    new Source(scaladsl.Source.fromFutureSource(future))

  /**
   * Streams the elements of an asynchronous source once its given [[CompletionStage]] completes.
   * If the [[CompletionStage]] fails the stream is failed with the exception from the future.
   * If downstream cancels before the stream completes the materialized [[CompletionStage]] will be failed
   * with a [[StreamDetachedException]]
   */
  @deprecated("Use 'Source.completionStageSource' (potentially together with `Source.fromGraph`) instead", "2.6.0")
  def fromSourceCompletionStage[T, M](
      completion: CompletionStage[_ <: Graph[SourceShape[T], M]]): javadsl.Source[T, CompletionStage[M]] =
    completionStageSource(completion.thenApply(fromGraph[T, M]))

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def tick[O](initialDelay: FiniteDuration, interval: FiniteDuration, tick: O): javadsl.Source[O, Cancellable] =
    new Source(scaladsl.Source.tick(initialDelay, interval, tick))

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  @silent("deprecated")
  def tick[O](initialDelay: java.time.Duration, interval: java.time.Duration, tick: O): javadsl.Source[O, Cancellable] =
    Source.tick(initialDelay.asScala, interval.asScala, tick)

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def single[T](element: T): Source[T, NotUsed] =
    new Source(scaladsl.Source.single(element))

  /**
   * Create a `Source` that will continually emit the given element.
   */
  def repeat[T](element: T): Source[T, NotUsed] =
    new Source(scaladsl.Source.repeat(element))

  /**
   * Create a `Source` that will unfold a value of type `S` into
   * a pair of the next state `S` and output elements of type `E`.
   */
  def unfold[S, E](s: S, f: function.Function[S, Optional[Pair[S, E]]]): Source[E, NotUsed] =
    new Source(scaladsl.Source.unfold(s)((s: S) => f.apply(s).asScala.map(_.toScala)))

  /**
   * Same as [[unfold]], but uses an async function to generate the next state-element tuple.
   */
  def unfoldAsync[S, E](s: S, f: function.Function[S, CompletionStage[Optional[Pair[S, E]]]]): Source[E, NotUsed] =
    new Source(scaladsl.Source.unfoldAsync(s)((s: S) =>
      f.apply(s).toScala.map(_.asScala.map(_.toScala))(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)))

  /**
   * Create a `Source` that immediately ends the stream with the `cause` failure to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, NotUsed] =
    new Source(scaladsl.Source.failed(cause))

  /**
   * Creates a `Source` that is not materialized until there is downstream demand, when the source gets materialized
   * the materialized future is completed with its value, if downstream cancels or fails without any demand the
   * `create` factory is never called and the materialized `CompletionStage` is failed.
   */
  @deprecated("Use 'Source.lazySource' instead", "2.6.0")
  def lazily[T, M](create: function.Creator[Source[T, M]]): Source[T, CompletionStage[M]] =
    scaladsl.Source.lazily[T, M](() => create.create().asScala).mapMaterializedValue(_.toJava).asJava

  /**
   * Creates a `Source` from supplied future factory that is not called until downstream demand. When source gets
   * materialized the materialized future is completed with the value from the factory. If downstream cancels or fails
   * without any demand the create factory is never called and the materialized `Future` is failed.
   *
   * @see [[Source.lazily]]
   */
  @deprecated("Use 'Source.lazyCompletionStage' instead", "2.6.0")
  def lazilyAsync[T](create: function.Creator[CompletionStage[T]]): Source[T, Future[NotUsed]] =
    scaladsl.Source.lazilyAsync[T](() => create.create().toScala).asJava

  /**
   * Emits a single value when the given Scala `Future` is successfully completed and then completes the stream.
   * The stream fails if the `Future` is completed with a failure.
   *
   * Here for Java interoperability, the normal use from Java should be [[Source.completionStage]]
   */
  def future[T](futureElement: Future[T]): Source[T, NotUsed] =
    scaladsl.Source.future(futureElement).asJava

  /**
   * Emits a single value when the given `CompletionStage` is successfully completed and then completes the stream.
   * If the `CompletionStage` is completed with a failure the stream is failed.
   */
  def completionStage[T](completionStage: CompletionStage[T]): Source[T, NotUsed] =
    future(completionStage.toScala)

  /**
   * Turn a `CompletionStage[Source]` into a source that will emit the values of the source when the future completes successfully.
   * If the `CompletionStage` is completed with a failure the stream is failed.
   */
  def completionStageSource[T, M](completionStageSource: CompletionStage[Source[T, M]]): Source[T, CompletionStage[M]] =
    scaladsl.Source
      .futureSource(completionStageSource.toScala.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext))
      .mapMaterializedValue(_.toJava)
      .asJava

  /**
   * Defers invoking the `create` function to create a single element until there is downstream demand.
   *
   * If the `create` function fails when invoked the stream is failed.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and will trigger the factory immediately.
   *
   * The materialized future `Done` value is completed when the `create` function has successfully been invoked,
   * if the function throws the future materialized value is failed with that exception.
   * If downstream cancels or fails before the function is invoked the materialized value
   * is failed with a [[akka.stream.NeverMaterializedException]]
   */
  def lazySingle[T](create: Creator[T]): Source[T, NotUsed] =
    lazySource(() => single(create.create())).mapMaterializedValue(_ => NotUsed)

  /**
   * Defers invoking the `create` function to create a future element until there is downstream demand.
   *
   * The returned future element will be emitted downstream when it completes, or fail the stream if the future
   * is failed or the `create` function itself fails.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and will trigger the factory immediately.
   *
   * The materialized future `Done` value is completed when the `create` function has successfully been invoked and the future completes,
   * if the function throws or the future fails the future materialized value is failed with that exception.
   * If downstream cancels or fails before the function is invoked the materialized value
   * is failed with a [[akka.stream.NeverMaterializedException]]
   */
  def lazyCompletionStage[T](create: Creator[CompletionStage[T]]): Source[T, NotUsed] =
    scaladsl.Source
      .lazySource { () =>
        val f = create.create().toScala
        scaladsl.Source.future(f)
      }
      .mapMaterializedValue(_ => NotUsed.notUsed())
      .asJava

  /**
   * Defers invoking the `create` function to create a future source until there is downstream demand.
   *
   * The returned source will emit downstream and behave just like it was the outer source. Downstream completes
   * when the created source completes and fails when the created source fails.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and will trigger the factory immediately.
   *
   * The materialized future value is completed with the materialized value of the created source when
   * it has been materialized. If the function throws or the source materialization fails the future materialized value
   * is failed with the thrown exception.
   *
   * If downstream cancels or fails before the function is invoked the materialized value
   * is failed with a [[akka.stream.NeverMaterializedException]]
   */
  def lazySource[T, M](create: Creator[Source[T, M]]): Source[T, CompletionStage[M]] =
    scaladsl.Source.lazySource(() => create.create().asScala).mapMaterializedValue(_.toJava).asJava

  /**
   *  Defers invoking the `create` function to create a future source until there is downstream demand.
   *
   * The returned future source will emit downstream and behave just like it was the outer source when the `CompletionStage` completes
   * successfully. Downstream completes when the created source completes and fails when the created source fails.
   * If the `CompletionStage` or the `create` function fails the stream is failed.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and triggers the factory immediately.
   *
   * The materialized `CompletionStage` value is completed with the materialized value of the created source when
   * it has been materialized. If the function throws or the source materialization fails the future materialized value
   * is failed with the thrown exception.
   *
   * If downstream cancels or fails before the function is invoked the materialized value
   * is failed with a [[akka.stream.NeverMaterializedException]]
   */
  def lazyCompletionStageSource[T, M](create: Creator[CompletionStage[Source[T, M]]]): Source[T, CompletionStage[M]] =
    lazySource[T, CompletionStage[M]](() => completionStageSource(create.create()))
      .mapMaterializedValue(_.thenCompose(csm => csm))

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def asSubscriber[T](): Source[T, Subscriber[T]] =
    new Source(scaladsl.Source.asSubscriber)

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
   * @param completionMatcher catches the completion message to end the stream
   * @param failureMatcher catches the failure message to fail the stream
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](
      completionMatcher: akka.japi.function.Function[Any, java.util.Optional[CompletionStrategy]],
      failureMatcher: akka.japi.function.Function[Any, java.util.Optional[Throwable]],
      bufferSize: Int,
      overflowStrategy: OverflowStrategy): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorRef(new JavaPartialFunction[Any, CompletionStrategy] {
      override def apply(x: Any, isCheck: Boolean): CompletionStrategy = {
        val result = completionMatcher(x)
        if (!result.isPresent) throw JavaPartialFunction.noMatch()
        else result.get()
      }
    }, new JavaPartialFunction[Any, Throwable] {
      override def apply(x: Any, isCheck: Boolean): Throwable = {
        val result = failureMatcher(x)
        if (!result.isPresent) throw JavaPartialFunction.noMatch()
        else result.get()
      }
    }, bufferSize, overflowStrategy))

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
   * The stream can be completed successfully by sending the actor reference a [[akka.actor.Status.Success]]
   * (whose content will be ignored) in which case already buffered elements will be signaled before signaling
   * completion.
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
   * Note that terminating the actor without first completing it, either with a success or a
   * failure, will prevent the actor triggering downstream completion and the stream will continue
   * to run even though the source actor is dead. Therefore you should **not** attempt to
   * manually terminate the actor such as with a [[akka.actor.PoisonPill]].
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * See also [[akka.stream.javadsl.Source.queue]].
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @Deprecated
  @deprecated("Use variant accepting completion and failure matchers", "2.6.0")
  def actorRef[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorRef({
      case akka.actor.Status.Success(s: CompletionStrategy) => s
      case akka.actor.Status.Success(_)                     => CompletionStrategy.Draining
      case akka.actor.Status.Success                        => CompletionStrategy.Draining
    }, { case akka.actor.Status.Failure(cause)              => cause }, bufferSize, overflowStrategy))

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * and a new message will only be accepted after the previous messages has been consumed and acknowledged back.
   * The stream will complete with failure if a message is sent before the acknowledgement has been replied back.
   *
   * The stream can be completed with failure by sending a message that is matched by `failureMatcher`. The extracted
   * [[Throwable]] will be used to fail the stream. In case the Actor is still draining its internal buffer (after having received
   * a message matched by `completionMatcher`) before signaling completion and it receives a message matched by `failureMatcher`,
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   */
  def actorRefWithBackpressure[T](
      ackMessage: Any,
      completionMatcher: akka.japi.function.Function[Any, java.util.Optional[CompletionStrategy]],
      failureMatcher: akka.japi.function.Function[Any, java.util.Optional[Throwable]]): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorRefWithBackpressure(ackMessage, new JavaPartialFunction[Any, CompletionStrategy] {
      override def apply(x: Any, isCheck: Boolean): CompletionStrategy = {
        val result = completionMatcher(x)
        if (!result.isPresent) throw JavaPartialFunction.noMatch()
        else result.get()
      }
    }, new JavaPartialFunction[Any, Throwable] {
      override def apply(x: Any, isCheck: Boolean): Throwable = {
        val result = failureMatcher(x)
        if (!result.isPresent) throw JavaPartialFunction.noMatch()
        else result.get()
      }
    }))

  /**
   * Creates a `Source` that is materialized as an [[akka.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * and a new message will only be accepted after the previous messages has been consumed and acknowledged back.
   * The stream will complete with failure if a message is sent before the acknowledgement has been replied back.
   *
   * The stream can be completed with failure by sending a message that is matched by `failureMatcher`. The extracted
   * [[Throwable]] will be used to fail the stream. In case the Actor is still draining its internal buffer (after having received
   * a message matched by `completionMatcher`) before signaling completion and it receives a message matched by `failureMatcher`,
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * @deprecated Use actorRefWithBackpressure instead
   */
  @Deprecated
  @deprecated("Use actorRefWithBackpressure instead", "2.6.0")
  def actorRefWithAck[T](
      ackMessage: Any,
      completionMatcher: akka.japi.function.Function[Any, java.util.Optional[CompletionStrategy]],
      failureMatcher: akka.japi.function.Function[Any, java.util.Optional[Throwable]]): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorRefWithBackpressure(ackMessage, new JavaPartialFunction[Any, CompletionStrategy] {
      override def apply(x: Any, isCheck: Boolean): CompletionStrategy = {
        val result = completionMatcher(x)
        if (!result.isPresent) throw JavaPartialFunction.noMatch()
        else result.get()
      }
    }, new JavaPartialFunction[Any, Throwable] {
      override def apply(x: Any, isCheck: Boolean): Throwable = {
        val result = failureMatcher(x)
        if (!result.isPresent) throw JavaPartialFunction.noMatch()
        else result.get()
      }
    }))

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
  @Deprecated
  @deprecated("Use actorRefWithBackpressure accepting completion and failure matchers", "2.6.0")
  def actorRefWithAck[T](ackMessage: Any): Source[T, ActorRef] =
    new Source(scaladsl.Source.actorRefWithBackpressure(ackMessage, {
      case akka.actor.Status.Success(s: CompletionStrategy) => s
      case akka.actor.Status.Success(_)                     => CompletionStrategy.Draining
      case akka.actor.Status.Success                        => CompletionStrategy.Draining
    }, { case akka.actor.Status.Failure(cause)              => cause }))

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SourceShape[T], M]): Source[T, M] =
    g match {
      case s: Source[T, M]                 => s
      case s if s eq scaladsl.Source.empty => empty().asInstanceOf[Source[T, M]]
      case other                           => new Source(scaladsl.Source.fromGraph(other))
    }

  /**
   * Defers the creation of a [[Source]] until materialization. The `factory` function
   * exposes [[Materializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[Source]] returned by this method.
   */
  def fromMaterializer[T, M](
      factory: BiFunction[Materializer, Attributes, Source[T, M]]): Source[T, CompletionStage[M]] =
    scaladsl.Source.fromMaterializer((mat, attr) => factory(mat, attr).asScala).mapMaterializedValue(_.toJava).asJava

  /**
   * Defers the creation of a [[Source]] until materialization. The `factory` function
   * exposes [[ActorMaterializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[Source]] returned by this method.
   */
  @deprecated("Use 'fromMaterializer' instead", "2.6.0")
  def setup[T, M](factory: BiFunction[ActorMaterializer, Attributes, Source[T, M]]): Source[T, CompletionStage[M]] =
    scaladsl.Source.setup((mat, attr) => factory(mat, attr).asScala).mapMaterializedValue(_.toJava).asJava

  /**
   * Combines several sources with fan-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  def combine[T, U](
      first: Source[T, _ <: Any],
      second: Source[T, _ <: Any],
      rest: java.util.List[Source[T, _ <: Any]],
      strategy: function.Function[java.lang.Integer, _ <: Graph[UniformFanInShape[T, U], NotUsed]])
      : Source[U, NotUsed] = {
    val seq = if (rest != null) Util.immutableSeq(rest).map(_.asScala) else immutable.Seq()
    new Source(scaladsl.Source.combine(first.asScala, second.asScala, seq: _*)(num => strategy.apply(num)))
  }

  /**
   * Combines two sources with fan-in strategy like `Merge` or `Concat` and returns `Source` with a materialized value.
   */
  def combineMat[T, U, M1, M2, M](
      first: Source[T, M1],
      second: Source[T, M2],
      strategy: function.Function[java.lang.Integer, _ <: Graph[UniformFanInShape[T, U], NotUsed]],
      combine: function.Function2[M1, M2, M]): Source[U, M] = {
    new Source(
      scaladsl.Source.combineMat(first.asScala, second.asScala)(num => strategy.apply(num))(combinerToScala(combine)))
  }

  /**
   * Combine the elements of multiple streams into a stream of lists.
   */
  def zipN[T](sources: java.util.List[Source[T, _ <: Any]]): Source[java.util.List[T], NotUsed] = {
    val seq = if (sources != null) Util.immutableSeq(sources).map(_.asScala) else immutable.Seq()
    new Source(scaladsl.Source.zipN(seq).map(_.asJava))
  }

  /*
   * Combine the elements of multiple streams into a stream of lists using a combiner function.
   */
  def zipWithN[T, O](
      zipper: function.Function[java.util.List[T], O],
      sources: java.util.List[Source[T, _ <: Any]]): Source[O, NotUsed] = {
    val seq = if (sources != null) Util.immutableSeq(sources).map(_.asScala) else immutable.Seq()
    new Source(scaladsl.Source.zipWithN[T, O](seq => zipper.apply(seq.asJava))(seq))
  }

  /**
   * Creates a `Source` that is materialized as an [[akka.stream.javadsl.SourceQueueWithComplete]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received. Elements in the buffer will be discarded
   * if downstream is terminated.
   *
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[akka.stream.javadsl.SourceQueueWithComplete.offer]] returns `CompletionStage<QueueOfferResult>` which completes with
   * `QueueOfferResult.enqueued` if element was added to buffer or sent downstream. It completes with
   * `QueueOfferResult.dropped` if element was dropped. Can also complete with `QueueOfferResult.Failure` -
   * when stream failed or `QueueOfferResult.QueueClosed` when downstream is completed.
   *
   * The strategy [[akka.stream.OverflowStrategy.backpressure]] will not complete last `offer():CompletionStage`
   * call when buffer is full.
   *
   * You can watch accessibility of stream with [[akka.stream.javadsl.SourceQueueWithComplete.watchCompletion]].
   * It returns a future that completes with success when this operator is completed or fails when stream is failed.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received message will wait
   * for downstream demand unless there is another message waiting for downstream demand, in that case
   * offer result will be completed according to the overflow strategy.
   *
   * SourceQueue that current source is materialized to is for single thread usage only.
   *
   * @param bufferSize size of buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def queue[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, SourceQueueWithComplete[T]] =
    new Source(scaladsl.Source.queue[T](bufferSize, overflowStrategy).mapMaterializedValue(_.asJava))

  /**
   * Start a new `Source` from some resource which can be opened, read and closed.
   * Interaction with resource happens in a blocking way.
   *
   * Example:
   * {{{
   * Source.unfoldResource(
   *   () -> new BufferedReader(new FileReader("...")),
   *   reader -> reader.readLine(),
   *   reader -> reader.close())
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
  def unfoldResource[T, S](
      create: function.Creator[S],
      read: function.Function[S, Optional[T]],
      close: function.Procedure[S]): javadsl.Source[T, NotUsed] =
    new Source(scaladsl.Source.unfoldResource[T, S](create.create _, (s: S) => read.apply(s).asScala, close.apply))

  /**
   * Start a new `Source` from some resource which can be opened, read and closed.
   * It's similar to `unfoldResource` but takes functions that return `CompletionStage` instead of plain values.
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
   *             is received. Stream calls close and completes when `CompletionStage` from read function returns None.
   * @param close - function that closes resource
   */
  def unfoldResourceAsync[T, S](
      create: function.Creator[CompletionStage[S]],
      read: function.Function[S, CompletionStage[Optional[T]]],
      close: function.Function[S, CompletionStage[Done]]): javadsl.Source[T, NotUsed] =
    new Source(
      scaladsl.Source.unfoldResourceAsync[T, S](
        () => create.create().toScala,
        (s: S) => read.apply(s).toScala.map(_.asScala)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext),
        (s: S) => close.apply(s).toScala))

  /**
   * Upcast a stream of elements to a stream of supertypes of that element. Useful in combination with
   * fan-in operators where you do not want to pay the cost of casting each element in a `map`.
   *
   * Example:
   *
   * {{{
   * Source<Apple, NotUsed> apples = Source.single(new Apple());
   * Source<Orange, NotUsed> oranges = Source.single(new Orange());
   * Source<Fruit, NotUsed> appleFruits = Source.upcast(apples);
   * Source<Fruit, NotUsed> orangeFruits = Source.upcast(oranges);
   *
   * Source<Fruit, NotUsed> fruits = appleFruits.merge(orangeFruits);
   * }}}
   *
   * @tparam SuperOut a supertype to the type of elements in stream
   * @return A source with the supertype as elements
   */
  def upcast[SuperOut, Out <: SuperOut, Mat](source: Source[Out, Mat]): Source[SuperOut, Mat] =
    source.asInstanceOf[Source[SuperOut, Mat]]

}

/**
 * Java API
 *
 * A `Source` is a set of stream processing steps that has one open output and an attached input.
 * Can be used as a `Publisher`
 */
final class Source[Out, Mat](delegate: scaladsl.Source[Out, Mat]) extends Graph[SourceShape[Out], Mat] {

  import akka.util.ccompat.JavaConverters._

  override def shape: SourceShape[Out] = delegate.shape

  override def traversalBuilder: LinearTraversalBuilder = delegate.traversalBuilder

  override def toString: String = delegate.toString

  /**
   * Converts this Java DSL element to its Scala DSL counterpart.
   */
  def asScala: scaladsl.Source[Out, Mat] = delegate

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): Source[Out, Mat2] =
    new Source(delegate.mapMaterializedValue(f.apply _))

  /**
   * Materializes this Source, immediately returning (1) its materialized value, and (2) a new Source
   * that can be used to consume elements from the newly materialized Source.
   *
   * Note that the `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def preMaterialize(systemProvider: ClassicActorSystemProvider)
      : Pair[Mat @uncheckedVariance, Source[Out @uncheckedVariance, NotUsed]] = {
    val (mat, src) = delegate.preMaterialize()(SystemMaterializer(systemProvider.classicSystem).materializer)
    Pair(mat, new Source(src))
  }

  /**
   * Materializes this Source, immediately returning (1) its materialized value, and (2) a new Source
   * that can be used to consume elements from the newly materialized Source.
   *
   * Prefer the method taking an `ActorSystem` unless you have special requirements.
   */
  def preMaterialize(
      materializer: Materializer): Pair[Mat @uncheckedVariance, Source[Out @uncheckedVariance, NotUsed]] = {
    val (mat, src) = delegate.preMaterialize()(materializer)
    Pair(mat, new Source(src))
  }

  /**
   * Transform this [[Source]] by appending the given processing operators.
   * {{{
   *     +----------------------------+
   *     | Resulting Source           |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   *     |  | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * `viaMat` if a different strategy is needed.
   */
  def via[T, M](flow: Graph[FlowShape[Out, T], M]): javadsl.Source[T, Mat] =
    new Source(delegate.via(flow))

  /**
   * Transform this [[Source]] by appending the given processing operators.
   * {{{
   *     +----------------------------+
   *     | Resulting Source           |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   *     |  | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting Flow.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def viaMat[T, M, M2](
      flow: Graph[FlowShape[Out, T], M],
      combine: function.Function2[Mat, M, M2]): javadsl.Source[T, M2] =
    new Source(delegate.viaMat(flow)(combinerToScala(combine)))

  /**
   * Connect this [[Source]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting RunnableGraph    |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   *     |  | this | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[Sink]] will be the materialized
   * value of the current flow (ignoring the given Sink’s value), use
   * `toMat` if a different strategy is needed.
   */
  def to[M](sink: Graph[SinkShape[Out], M]): javadsl.RunnableGraph[Mat] =
    RunnableGraph.fromGraph(delegate.to(sink))

  /**
   * Connect this [[Source]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting RunnableGraph    |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   *     |  | this | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Sink into the materialized value of the resulting Sink.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def toMat[M, M2](sink: Graph[SinkShape[Out], M], combine: function.Function2[Mat, M, M2]): javadsl.RunnableGraph[M2] =
    RunnableGraph.fromGraph(delegate.toMat(sink)(combinerToScala(combine)))

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a `Sink.asPublisher`.
   *
   * Note that the classic or typed `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def runWith[M](sink: Graph[SinkShape[Out], M], systemProvider: ClassicActorSystemProvider): M =
    delegate.runWith(sink)(SystemMaterializer(systemProvider.classicSystem).materializer)

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a `Sink.asPublisher`.
   *
   * Prefer the method taking an `ActorSystem` unless you have special requirements
   */
  def runWith[M](sink: Graph[SinkShape[Out], M], materializer: Materializer): M =
    delegate.runWith(sink)(materializer)

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   *
   * Note that the classic or typed `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def runFold[U](
      zero: U,
      f: function.Function2[U, Out, U],
      systemProvider: ClassicActorSystemProvider): CompletionStage[U] =
    runWith(Sink.fold(zero, f), systemProvider)

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   *
   * Prefer the method taking an ActorSystem unless you have special requirements.
   */
  def runFold[U](zero: U, f: function.Function2[U, Out, U], materializer: Materializer): CompletionStage[U] =
    runWith(Sink.fold(zero, f), materializer)

  /**
   * Shortcut for running this `Source` with an asynchronous fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   *
   * Note that the classic or typed `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def runFoldAsync[U](
      zero: U,
      f: function.Function2[U, Out, CompletionStage[U]],
      systemProvider: ClassicActorSystemProvider): CompletionStage[U] = runWith(Sink.foldAsync(zero, f), systemProvider)

  /**
   * Shortcut for running this `Source` with an asynchronous fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   *
   * Prefer the method taking an `ActorSystem` unless you have special requirements
   */
  def runFoldAsync[U](
      zero: U,
      f: function.Function2[U, Out, CompletionStage[U]],
      materializer: Materializer): CompletionStage[U] = runWith(Sink.foldAsync(zero, f), materializer)

  /**
   * Shortcut for running this `Source` with a reduce function.
   * The given function is invoked for every received element, giving it its previous
   * output (from the second ones) an the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * Note that the classic or typed `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def runReduce(
      f: function.Function2[Out, Out, Out],
      systemProvider: ClassicActorSystemProvider): CompletionStage[Out] =
    runWith(Sink.reduce(f), systemProvider.classicSystem)

  /**
   * Shortcut for running this `Source` with a reduce function.
   * The given function is invoked for every received element, giving it its previous
   * output (from the second ones) an the element as input.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure is signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * Prefer the method taking an `ActorSystem` unless you have special requirements
   */
  def runReduce(f: function.Function2[Out, Out, Out], materializer: Materializer): CompletionStage[Out] =
    runWith(Sink.reduce(f), materializer)

  /**
   * Concatenate this [[Source]] with the given one, meaning that once current
   * is exhausted and all result elements have been generated,
   * the given source elements will be produced.
   *
   * Note that given [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Source]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * '''Emits when''' element is available from current source or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def concat[M](that: Graph[SourceShape[Out], M]): javadsl.Source[Out, Mat] =
    new Source(delegate.concat(that))

  /**
   * Concatenate this [[Source]] with the given one, meaning that once current
   * is exhausted and all result elements have been generated,
   * the given source elements will be produced.
   *
   * Note that given [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Source]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#concat]].
   */
  def concatMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out, M2] =
    new Source(delegate.concatMat(that)(combinerToScala(matF)))

  /**
   * Prepend the given [[Source]] to this one, meaning that once the given source
   * is exhausted and all result elements have been generated, the current source's
   * elements will be produced.
   *
   * Note that the current [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Source]] will be pulled.
   *
   * '''Emits when''' element is available from current source or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def prepend[M](that: Graph[SourceShape[Out], M]): javadsl.Source[Out, Mat] =
    new Source(delegate.prepend(that))

  /**
   * Prepend the given [[Source]] to this one, meaning that once the given source
   * is exhausted and all result elements have been generated, the current source's
   * elements will be produced.
   *
   * Note that the current [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Source]] will be pulled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#prepend]].
   */
  def prependMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out, M2] =
    new Source(delegate.prependMat(that)(combinerToScala(matF)))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes or it gets
   * cancelled.
   *
   * On errors the operator is failed regardless of source of the error.
   *
   * '''Emits when''' element is available from first stream or first stream closed without emitting any elements and an element
   *                  is available from the second stream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the primary stream completes after emitting at least one element, when the primary stream completes
   *                      without emitting and the secondary stream already has completed or when the secondary stream completes
   *
   * '''Cancels when''' downstream cancels and additionally the alternative is cancelled as soon as an element passes
   *                    by from this stream.
   */
  def orElse[M](secondary: Graph[SourceShape[Out], M]): javadsl.Source[Out, Mat] =
    new Source(delegate.orElse(secondary))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#orElse]]
   */
  def orElseMat[M, M2](
      secondary: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out, M2] =
    new Source(delegate.orElseMat(secondary)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * It is similar to [[#wireTap]] but will backpressure instead of dropping elements when the given [[Sink]] is not ready.
   *
   * '''Emits when''' element is available and demand exists both from the Sink and the downstream.
   *
   * '''Backpressures when''' downstream or Sink backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream or Sink cancels
   */
  def alsoTo(that: Graph[SinkShape[Out], _]): javadsl.Source[Out, Mat] =
    new Source(delegate.alsoTo(that))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * It is similar to [[#wireTapMat]] but will backpressure instead of dropping elements when the given [[Sink]] is not ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#alsoTo]]
   */
  def alsoToMat[M2, M3](
      that: Graph[SinkShape[Out], M2],
      matF: function.Function2[Mat, M2, M3]): javadsl.Source[Out, M3] =
    new Source(delegate.alsoToMat(that)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements will be sent to the [[Sink]]
   * instead of being passed through if the predicate `when` returns `true`.
   *
   * '''Emits when''' emits when an element is available from the input and the chosen output has demand
   *
   * '''Backpressures when''' the currently chosen output back-pressures
   *
   * '''Completes when''' upstream completes and no output is pending
   *
   * '''Cancels when''' any of the downstreams cancel
   */
  def divertTo(that: Graph[SinkShape[Out], _], when: function.Predicate[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.divertTo(that, when.test))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements will be sent to the [[Sink]]
   * instead of being passed through if the predicate `when` returns `true`.
   *
   * @see [[#divertTo]]
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def divertToMat[M2, M3](
      that: Graph[SinkShape[Out], M2],
      when: function.Predicate[Out],
      matF: function.Function2[Mat, M2, M3]): javadsl.Source[Out, M3] =
    new Source(delegate.divertToMat(that, when.test)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]] as a wire tap, meaning that elements that pass
   * through will also be sent to the wire-tap Sink, without the latter affecting the mainline flow.
   * If the wire-tap Sink backpressures, elements that would've been sent to it will be dropped instead.
   *
   * It is similar to [[#alsoTo]] which does backpressure instead of dropping elements.
   *
   * '''Emits when''' element is available and demand exists from the downstream; the element will
   * also be sent to the wire-tap Sink if there is demand.
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def wireTap(that: Graph[SinkShape[Out], _]): javadsl.Source[Out, Mat] =
    new Source(delegate.wireTap(that))

  /**
   * Attaches the given [[Sink]] to this [[Flow]] as a wire tap, meaning that elements that pass
   * through will also be sent to the wire-tap Sink, without the latter affecting the mainline flow.
   * If the wire-tap Sink backpressures, elements that would've been sent to it will be dropped instead.
   *
   * It is similar to [[#alsoToMat]] which does backpressure instead of dropping elements.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#wireTap]]
   */
  def wireTapMat[M2, M3](
      that: Graph[SinkShape[Out], M2],
      matF: function.Function2[Mat, M2, M3]): javadsl.Source[Out, M3] =
    new Source(delegate.wireTapMat(that)(combinerToScala(matF)))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Source]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * Example:
   * {{{
   * Source.from(Arrays.asList(1, 2, 3)).interleave(Source.from(Arrays.asList(4, 5, 6, 7), 2)
   * // 1, 2, 4, 5, 3, 6, 7
   * }}}
   *
   * After one of sources is complete than all the rest elements will be emitted from the second one
   *
   * If one of sources gets upstream error - stream completes with failure.
   *
   * '''Emits when''' element is available from the currently consumed upstream
   *
   * '''Backpressures when''' downstream backpressures. Signal to current
   * upstream, switch to next upstream when received `segmentSize` elements
   *
   * '''Completes when''' this [[Source]] and given one completes
   *
   * '''Cancels when''' downstream cancels
   */
  def interleave(that: Graph[SourceShape[Out], _], segmentSize: Int): javadsl.Source[Out, Mat] =
    new Source(delegate.interleave(that, segmentSize))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * If eagerClose is false and one of the upstreams complete the elements from the other upstream will continue passing
   * through the interleave operator. If eagerClose is true and one of the upstream complete interleave will cancel the
   * other upstream and complete itself.
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * '''Emits when''' element is available from the currently consumed upstream
   *
   * '''Backpressures when''' downstream backpressures. Signal to current
   * upstream, switch to next upstream when received `segmentSize` elements
   *
   * '''Completes when''' the [[Flow]] and given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def interleave(that: Graph[SourceShape[Out], _], segmentSize: Int, eagerClose: Boolean): javadsl.Source[Out, Mat] =
    new Source(delegate.interleave(that, segmentSize, eagerClose))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Source]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * After one of sources is complete than all the rest elements will be emitted from the second one
   *
   * If one of sources gets upstream error - stream completes with failure.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#interleave]].
   */
  def interleaveMat[M, M2](
      that: Graph[SourceShape[Out], M],
      segmentSize: Int,
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out, M2] =
    new Source(delegate.interleaveMat(that, segmentSize)(combinerToScala(matF)))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Source]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * If eagerClose is false and one of the upstreams complete the elements from the other upstream will continue passing
   * through the interleave operator. If eagerClose is true and one of the upstream complete interleave will cancel the
   * other upstream and complete itself.
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#interleave]]
   */
  def interleaveMat[M, M2](
      that: Graph[SourceShape[Out], M],
      segmentSize: Int,
      eagerClose: Boolean,
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out, M2] =
    new Source(delegate.interleaveMat(that, segmentSize, eagerClose)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def merge(that: Graph[SourceShape[Out], _]): javadsl.Source[Out, Mat] =
    new Source(delegate.merge(that))

  /**
   * Merge the given [[Source]] to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete (eagerComplete=false) or one upstream completes (eagerComplete=true), default value is `false`
   *
   * '''Cancels when''' downstream cancels
   */
  def merge(that: Graph[SourceShape[Out], _], eagerComplete: Boolean): javadsl.Source[Out, Mat] =
    new Source(delegate.merge(that, eagerComplete))

  /**
   * Merge the given [[Source]] to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#merge]].
   */
  def mergeMat[M, M2](that: Graph[SourceShape[Out], M], matF: function.Function2[Mat, M, M2]): javadsl.Source[Out, M2] =
    new Source(delegate.mergeMat(that)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#merge]]
   */
  def mergeMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2],
      eagerComplete: Boolean): javadsl.Source[Out, M2] =
    new Source(delegate.mergeMat(that, eagerComplete)(combinerToScala(matF)))

  /**
   * MergeLatest joins elements from N input streams into stream of lists of size N.
   * i-th element in list is the latest emitted element from i-th input stream.
   * MergeLatest emits list for each element emitted from some input stream,
   * but only after each input stream emitted at least one element.
   *
   * '''Emits when''' an element is available from some input and each input emits at least one element from stream start
   *
   * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
   */
  def mergeLatest[M](
      that: Graph[SourceShape[Out], M],
      eagerComplete: Boolean): javadsl.Source[java.util.List[Out], Mat] =
    new Source(delegate.mergeLatest(that, eagerComplete).map(_.asJava))

  /**
   * MergeLatest joins elements from N input streams into stream of lists of size N.
   * i-th element in list is the latest emitted element from i-th input stream.
   * MergeLatest emits list for each element emitted from some input stream,
   * but only after each input stream emitted at least one element.
   *
   * @see [[#mergeLatest]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def mergeLatestMat[Mat2, Mat3](
      that: Graph[SourceShape[Out], Mat2],
      eagerComplete: Boolean,
      matF: function.Function2[Mat, Mat2, Mat3]): javadsl.Source[java.util.List[Out], Mat3] =
    new Source(delegate.mergeLatestMat(that, eagerComplete)(combinerToScala(matF))).map(_.asJava)

  /**
   * Merge two sources. Prefer one source if both sources have elements ready.
   *
   * '''emits''' when one of the inputs has an element available. If multiple have elements available, prefer the 'right' one when 'preferred' is 'true', or the 'left' one when 'preferred' is 'false'.
   *
   * '''backpressures''' when downstream backpressures
   *
   * '''completes''' when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)
   */
  def mergePreferred[M](
      that: Graph[SourceShape[Out], M],
      preferred: Boolean,
      eagerComplete: Boolean): javadsl.Source[Out, Mat] =
    new Source(delegate.mergePreferred(that, preferred, eagerComplete))

  /**
   * Merge two sources. Prefer one source if both sources have elements ready.
   *
   * @see [[#mergePreferred]]
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def mergePreferredMat[Mat2, Mat3](
      that: Graph[SourceShape[Out], Mat2],
      preferred: Boolean,
      eagerComplete: Boolean,
      matF: function.Function2[Mat, Mat2, Mat3]): javadsl.Source[Out, Mat3] =
    new Source(delegate.mergePreferredMat(that, preferred, eagerComplete)(combinerToScala(matF)))

  /**
   * Merge two sources. Prefer the sources depending on the 'priority' parameters.
   *
   * '''emits''' when one of the inputs has an element available, preferring inputs based on the 'priority' parameters if both have elements available
   *
   * '''backpressures''' when downstream backpressures
   *
   * '''completes''' when both upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)
   */
  def mergePrioritized[M](
      that: Graph[SourceShape[Out], M],
      leftPriority: Int,
      rightPriority: Int,
      eagerComplete: Boolean): javadsl.Source[Out, Mat] =
    new Source(delegate.mergePrioritized(that, leftPriority, rightPriority, eagerComplete))

  /**
   * Merge multiple sources. Prefer the sources depending on the 'priority' parameters.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def mergePrioritizedMat[Mat2, Mat3](
      that: Graph[SourceShape[Out], Mat2],
      leftPriority: Int,
      rightPriority: Int,
      eagerComplete: Boolean,
      matF: function.Function2[Mat, Mat2, Mat3]): javadsl.Source[Out, Mat3] =
    new Source(delegate.mergePrioritizedMat(that, leftPriority, rightPriority, eagerComplete)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to this [[Source]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def mergeSorted[M](that: Graph[SourceShape[Out], M], comp: util.Comparator[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.mergeSorted(that)(Ordering.comparatorToOrdering(comp)))

  /**
   * Merge the given [[Source]] to this [[Source]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#mergeSorted]].
   */
  def mergeSortedMat[Mat2, Mat3](
      that: Graph[SourceShape[Out], Mat2],
      comp: util.Comparator[Out],
      matF: function.Function2[Mat, Mat2, Mat3]): javadsl.Source[Out, Mat3] =
    new Source(delegate.mergeSortedMat(that)(combinerToScala(matF))(Ordering.comparatorToOrdering(comp)))

  /**
   * Combine the elements of current [[Source]] and the given one into a stream of tuples.
   *
   * '''Emits when''' all of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zip[T](that: Graph[SourceShape[T], _]): javadsl.Source[Out @uncheckedVariance Pair T, Mat] =
    zipMat(that, Keep.left)

  /**
   * Combine the elements of current [[Source]] and the given one into a stream of tuples.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zip]].
   */
  def zipMat[T, M, M2](
      that: Graph[SourceShape[T], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out @uncheckedVariance Pair T, M2] =
    this.viaMat(Flow.create[Out].zipMat(that, Keep.right[NotUsed, M]), matF)

  /**
   * Combine the elements of current flow and the given [[Source]] into a stream of tuples.
   *
   * '''Emits when''' at first emits when both inputs emit, and then as long as any input emits (coupled to the default value of the completed input).
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipAll[U, A >: Out](that: Graph[SourceShape[U], _], thisElem: A, thatElem: U): Source[Pair[A, U], Mat] =
    new Source(delegate.zipAll(that, thisElem, thatElem).map { case (a, u) => Pair.create(a, u) })

  /**
   * Combine the elements of current flow and the given [[Source]] into a stream of tuples.
   *
   * @see [[#zipAll]]
   *
   * '''Emits when''' at first emits when both inputs emit, and then as long as any input emits (coupled to the default value of the completed input).
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipAllMat[U, Mat2, Mat3, A >: Out](that: Graph[SourceShape[U], Mat2], thisElem: A, thatElem: U)(
      matF: (Mat, Mat2) => Mat3): Source[Pair[A, U], Mat3] =
    new Source(delegate.zipAllMat(that, thisElem, thatElem)(matF).map { case (a, u) => Pair.create(a, u) })

  /**
   * Combine the elements of 2 streams into a stream of tuples, picking always the latest element of each.
   *
   * A `ZipLatest` has a `left` and a `right` input port and one `out` port.
   *
   * No element is emitted until at least one element from each Source becomes available.
   *
   * '''Emits when''' all of the inputs have at least an element available, and then each time an element becomes
   * *   available on either of the inputs
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipLatest[T](that: Graph[SourceShape[T], _]): javadsl.Source[Out @uncheckedVariance Pair T, Mat] =
    zipLatestMat(that, Keep.left)

  /**
   * Combine the elements of current [[Source]] and the given one into a stream of tuples, picking always the latest element of each.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipLatest]].
   */
  def zipLatestMat[T, M, M2](
      that: Graph[SourceShape[T], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out @uncheckedVariance Pair T, M2] =
    this.viaMat(Flow.create[Out].zipLatestMat(that, Keep.right[NotUsed, M]), matF)

  /**
   * Put together the elements of current [[Source]] and the given one
   * into a stream of combined elements using a combiner function.
   *
   * '''Emits when''' all of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWith[Out2, Out3](
      that: Graph[SourceShape[Out2], _],
      combine: function.Function2[Out, Out2, Out3]): javadsl.Source[Out3, Mat] =
    new Source(delegate.zipWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Source]] and the given one
   * into a stream of combined elements using a combiner function.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipWith]].
   */
  def zipWithMat[Out2, Out3, M, M2](
      that: Graph[SourceShape[Out2], M],
      combine: function.Function2[Out, Out2, Out3],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out3, M2] =
    new Source(delegate.zipWithMat[Out2, Out3, M, M2](that)(combinerToScala(combine))(combinerToScala(matF)))

  /**
   * Combine the elements of multiple streams into a stream of combined elements using a combiner function,
   * picking always the latest of the elements of each source.
   *
   * No element is emitted until at least one element from each Source becomes available. Whenever a new
   * element appears, the zipping function is invoked with a tuple containing the new element
   * and the other last seen elements.
   *
   *   '''Emits when''' all of the inputs have at least an element available, and then each time an element becomes
   *   available on either of the inputs
   *
   *   '''Backpressures when''' downstream backpressures
   *
   *   '''Completes when''' any of the upstreams completes
   *
   *   '''Cancels when''' downstream cancels
   */
  def zipLatestWith[Out2, Out3](
      that: Graph[SourceShape[Out2], _],
      combine: function.Function2[Out, Out2, Out3]): javadsl.Source[Out3, Mat] =
    new Source(delegate.zipLatestWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Source]] and the given one
   * into a stream of combined elements using a combiner function,
   * picking always the latest of the elements of each source.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipLatestWith]].
   */
  def zipLatestWithMat[Out2, Out3, M, M2](
      that: Graph[SourceShape[Out2], M],
      combine: function.Function2[Out, Out2, Out3],
      matF: function.Function2[Mat, M, M2]): javadsl.Source[Out3, M2] =
    new Source(delegate.zipLatestWithMat[Out2, Out3, M, M2](that)(combinerToScala(combine))(combinerToScala(matF)))

  /**
   * Combine the elements of current [[Source]] into a stream of tuples consisting
   * of all elements paired with their index. Indices start at 0.
   *
   * '''Emits when''' upstream emits an element and is paired with their index
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWithIndex: javadsl.Source[Pair[Out @uncheckedVariance, java.lang.Long], Mat] =
    new Source(delegate.zipWithIndex.map { case (elem, index) => Pair[Out, java.lang.Long](elem, index) })

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed normally when reaching the
   * normal end of the stream, or completed exceptionally if there is a failure is signaled in
   * the stream.
   *
   * Note that the classic or typed `ActorSystem` can be used as the `systemProvider` parameter.
   */
  def runForeach(f: function.Procedure[Out], systemProvider: ClassicActorSystemProvider): CompletionStage[Done] =
    runWith(Sink.foreach(f), systemProvider)

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[java.util.concurrent.CompletionStage]] will be completed normally when reaching the
   * normal end of the stream, or completed exceptionally if there is a failure is signaled in
   * the stream.
   *
   * Prefer the method taking an `ActorSystem` unless you have special requirements
   */
  def runForeach(f: function.Procedure[Out], materializer: Materializer): CompletionStage[Done] =
    runWith(Sink.foreach(f), materializer)

  // COMMON OPS //

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def map[T](f: function.Function[Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.map(f.apply))

  /**
   * This is a simplified version of `wireTap(Sink)` that takes only a simple procedure.
   * Elements will be passed into this "side channel" function, and any of its results will be ignored.
   *
   * If the wire-tap operation is slow (it backpressures), elements that would've been sent to it will be dropped instead.
   *
   * It is similar to [[#alsoTo]] which does backpressure instead of dropping elements.
   *
   * This operation is useful for inspecting the passed through element, usually by means of side-effecting
   * operations (such as `println`, or emitting metrics), for each element without having to modify it.
   *
   * For logging signals (elements, completion, error) consider using the [[log]] operator instead,
   * along with appropriate `ActorAttributes.createLogLevels`.
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels; Note that failures of the `f` function will not cause cancellation
   */
  def wireTap(f: function.Procedure[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.wireTap(f(_)))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recover` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use recoverWithRetries instead.", "2.4.4")
  def recover(pf: PartialFunction[Throwable, Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.recover(pf))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recover` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use recoverWithRetries instead.", "2.4.4")
  def recover(clazz: Class[_ <: Throwable], supplier: Supplier[Out]): javadsl.Source[Out, Mat] =
    recover {
      case elem if clazz.isInstance(elem) => supplier.get()
    }

  /**
   * While similar to [[recover]] this operator can be used to transform an error signal to a different one *without* logging
   * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
   * would log the `t2` error.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Similarly to [[recover]] throwing an exception inside `mapError` _will_ be logged.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def mapError(pf: PartialFunction[Throwable, Throwable]): javadsl.Source[Out, Mat] =
    new Source(delegate.mapError(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWith` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @silent("deprecated")
  def recoverWith(pf: PartialFunction[Throwable, _ <: Graph[SourceShape[Out], NotUsed]]): Source[Out, Mat] =
    new Source(delegate.recoverWith(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWith` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def recoverWith(
      clazz: Class[_ <: Throwable],
      supplier: Supplier[Graph[SourceShape[Out], NotUsed]]): Source[Out, Mat] =
    recoverWith {
      case elem if clazz.isInstance(elem) => supplier.get()
    }

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all.
   *
   * A negative `attempts` number is interpreted as "infinite", which results in the exact same behavior as `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWithRetries` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def recoverWithRetries(
      attempts: Int,
      pf: PartialFunction[Throwable, _ <: Graph[SourceShape[Out], NotUsed]]): Source[Out, Mat] =
    new Source(delegate.recoverWithRetries(attempts, pf))

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all.
   *
   * A negative `attempts` number is interpreted as "infinite", which results in the exact same behavior as `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWithRetries` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   * @param attempts Maximum number of retries or -1 to retry indefinitely
   * @param clazz the class object of the failure cause
   * @param supplier supply the new Source to be materialized
   */
  def recoverWithRetries(
      attempts: Int,
      clazz: Class[_ <: Throwable],
      supplier: Supplier[Graph[SourceShape[Out], NotUsed]]): Source[Out, Mat] =
    recoverWithRetries(attempts, {
      case elem if clazz.isInstance(elem) => supplier.get()
    })

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream.
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def mapConcat[T](f: function.Function[Out, _ <: java.lang.Iterable[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.mapConcat(elem => Util.immutableSeq(f.apply(elem))))

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream. The transformation is meant to be stateful,
   * which is enabled by creating the transformation function anew for every materialization —
   * the returned function will typically close over mutable objects to store state between
   * invocations. For the stateless variant see [[#mapConcat]].
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def statefulMapConcat[T](f: function.Creator[function.Function[Out, java.lang.Iterable[T]]]): javadsl.Source[T, Mat] =
    new Source(delegate.statefulMapConcat { () =>
      val fun = f.create()
      elem => Util.immutableSeq(fun(elem))
    })

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsync``.
   * These CompletionStages may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the CompletionStage returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of CompletionStages reaches the configured parallelism and the downstream
   * backpressures or the first CompletionStage is not completed
   *
   * '''Completes when''' upstream completes and all CompletionStages has been completed and all elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.mapAsync(parallelism)(x => f(x).toScala))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsyncUnordered``.
   * Each processed element will be emitted downstream as soon as it is ready, i.e. it is possible
   * that the elements are not emitted downstream in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive (even though the result of the CompletionStages
   * returned by `f` might be emitted in a different order).
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of CompletionStages reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all CompletionStages has been completed and all elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.mapAsyncUnordered(parallelism)(x => f(x).toScala))

  /**
   * Use the `ask` pattern to send a request-reply message to the target `ref` actor.
   * If any of the asks times out it will fail the stream with a [[akka.pattern.AskTimeoutException]].
   *
   * The `mapTo` class parameter is used to cast the incoming responses to the expected response type.
   *
   * Similar to the plain ask pattern, the target actor is allowed to reply with `akka.util.Status`.
   * An `akka.util.Status#Failure` will cause the operator to fail with the cause carried in the `Failure` message.
   *
   * Defaults to parallelism of 2 messages in flight, since while one ask message may be being worked on, the second one
   * still be in the mailbox, so defaulting to sending the second one a bit earlier than when first ask has replied maintains
   * a slightly healthier throughput.
   *
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Fails when''' the passed in actor terminates, or a timeout is exceeded in any of the asks performed
   *
   * '''Cancels when''' downstream cancels
   */
  def ask[S](ref: ActorRef, mapTo: Class[S], timeout: Timeout): javadsl.Source[S, Mat] =
    ask(2, ref, mapTo, timeout)

  /**
   * Use the `ask` pattern to send a request-reply message to the target `ref` actor.
   * If any of the asks times out it will fail the stream with a [[akka.pattern.AskTimeoutException]].
   *
   * The `mapTo` class parameter is used to cast the incoming responses to the expected response type.
   *
   * Similar to the plain ask pattern, the target actor is allowed to reply with `akka.util.Status`.
   * An `akka.util.Status#Failure` will cause the operator to fail with the cause carried in the `Failure` message.
   *
   * Parallelism limits the number of how many asks can be "in flight" at the same time.
   * Please note that the elements emitted by this operator are in-order with regards to the asks being issued
   * (i.e. same behaviour as mapAsync).
   *
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Fails when''' the passed in actor terminates, or a timeout is exceeded in any of the asks performed
   *
   * '''Cancels when''' downstream cancels
   */
  def ask[S](parallelism: Int, ref: ActorRef, mapTo: Class[S], timeout: Timeout): javadsl.Source[S, Mat] =
    new Source(delegate.ask[S](parallelism)(ref)(timeout, ClassTag(mapTo)))

  /**
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated.
   *
   * '''Emits when''' upstream emits
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Fails when''' the watched actor terminates
   *
   * '''Cancels when''' downstream cancels
   */
  def watch(ref: ActorRef): javadsl.Source[Out, Mat] =
    new Source(delegate.watch(ref))

  /**
   * Only pass on those elements that satisfy the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the given predicate returns true for the element
   *
   * '''Backpressures when''' the given predicate returns true for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def filter(p: function.Predicate[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.filter(p.test))

  /**
   * Only pass on those elements that NOT satisfy the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the given predicate returns false for the element
   *
   * '''Backpressures when''' the given predicate returns false for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filterNot(p: function.Predicate[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.filterNot(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.collect(pf))

  /**
   * Transform this stream by testing the type of each of the elements
   * on which the element is an instance of the provided type as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the element is an instance of the provided type
   *
   * '''Backpressures when''' the element is an instance of the provided type and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collectType[T](clazz: Class[T]): javadsl.Source[T, Mat] =
    new Source(delegate.collectType[T](ClassTag[T](clazz)))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' the specified number of elements has been accumulated or upstream completed
   *
   * '''Backpressures when''' a group has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def grouped(n: Int): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.grouped(n).map(_.asJava))

  /**
   * Ensure stream boundedness by limiting the number of elements from upstream.
   * If the number of incoming elements exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limit(n: Int): javadsl.Source[Out, Mat] = new Source(delegate.limit(n))

  /**
   * Ensure stream boundedness by evaluating the cost of incoming elements
   * using a cost function. Exactly how many elements will be allowed to travel downstream depends on the
   * evaluated cost of each element. If the accumulated cost exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limitWeighted(n: Long)(costFn: function.Function[Out, java.lang.Long]): javadsl.Source[Out, Mat] = {
    new Source(delegate.limitWeighted(n)(costFn.apply))
  }

  /**
   * Apply a sliding window over the stream and return the windows as groups of elements, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   * `step` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' enough elements have been collected within the window or upstream completed
   *
   * '''Backpressures when''' a window has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def sliding(n: Int, step: Int): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.sliding(n, step).map(_.asJava))

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' the function scanning the element returns a new element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def scan[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.scan(zero)(f.apply))

  /**
   * Similar to `scan` but with a asynchronous function,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting a `Future` that resolves to the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Resume]] current value starts at the previous
   * current value, or zero when it doesn't have one, and the stream will continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' the future returned by f` completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and the last future returned by `f` completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.scan]]
   */
  def scanAsync[T](zero: T)(f: function.Function2[T, Out, CompletionStage[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.scanAsync(zero) { (out, in) =>
      f(out, in).toScala
    })

  /**
   * Similar to `scan` but only emits its result when the upstream completes,
   * after which it also completes. Applies the given function `f` towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def fold[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Source[T, Mat] =
    new Source(delegate.fold(zero)(f.apply))

  /**
   * Similar to `fold` but with an asynchronous function.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * If the function `f` returns a failure and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def foldAsync[T](zero: T)(f: function.Function2[T, Out, CompletionStage[T]]): javadsl.Source[T, Mat] =
    new Source(delegate.foldAsync(zero) { (out, in) =>
      f(out, in).toScala
    })

  /**
   * Similar to `fold` but uses first element as zero element.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def reduce(f: function.Function2[Out, Out, Out @uncheckedVariance]): javadsl.Source[Out, Mat] =
    new Source(delegate.reduce(f.apply))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * In case you want to only prepend or only append an element (yet still use the `intercept` feature
   * to inject a separator between elements, you may want to use the following pattern instead of the 3-argument
   * version of intersperse (See [[Source.concat]] for semantics details):
   *
   * {{{
   * Source.single(">> ").concat(list.intersperse(","))
   * list.intersperse(",").concat(Source.single("END"))
   * }}}
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse(start: Out, inject: Out, end: Out): javadsl.Source[Out, Mat] =
    new Source(delegate.intersperse(start, inject, end))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse(inject: Out): javadsl.Source[Out, Mat] =
    new Source(delegate.intersperse(inject))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or `n` elements is buffered
   *
   * '''Backpressures when''' downstream backpressures, and there are `n+1` buffered elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.groupedWithin(n, d).map(_.asJava)) // TODO optimize to one step

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or `n` elements is buffered
   *
   * '''Backpressures when''' downstream backpressures, and there are `n+1` buffered elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @silent("deprecated")
  def groupedWithin(n: Int, d: java.time.Duration): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    groupedWithin(n, d.asScala)

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than `maxWeight`
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def groupedWeightedWithin(
      maxWeight: Long,
      costFn: function.Function[Out, java.lang.Long],
      d: FiniteDuration): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    new Source(delegate.groupedWeightedWithin(maxWeight, d)(costFn.apply).map(_.asJava))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than `maxWeight`
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @silent("deprecated")
  def groupedWeightedWithin(
      maxWeight: Long,
      costFn: function.Function[Out, java.lang.Long],
      d: java.time.Duration): javadsl.Source[java.util.List[Out @uncheckedVariance], Mat] =
    groupedWeightedWithin(maxWeight, costFn, d.asScala)

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[akka.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `withAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param of time to shift all messages
   * @param strategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def delay(of: FiniteDuration, strategy: DelayOverflowStrategy): Source[Out, Mat] =
    new Source(delegate.delay(of, strategy))

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[akka.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `withAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param of time to shift all messages
   * @param strategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @silent("deprecated")
  def delay(of: java.time.Duration, strategy: DelayOverflowStrategy): Source[Out, Mat] =
    delay(of.asScala, strategy)

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   *
   * '''Emits when''' the specified number of elements has been dropped already
   *
   * '''Backpressures when''' the specified number of elements has been dropped and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def drop(n: Long): javadsl.Source[Out, Mat] =
    new Source(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   *
   * '''Emits when''' the specified time elapsed and a new upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def dropWithin(d: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.dropWithin(d))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   *
   * '''Emits when''' the specified time elapsed and a new upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def dropWithin(d: java.time.Duration): javadsl.Source[Out, Mat] =
    dropWithin(d.asScala)

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time, including the first failed element if inclusive is true
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[Source.limit]], [[Source.limitWeighted]]
   */
  def takeWhile(p: function.Predicate[Out], inclusive: Boolean): javadsl.Source[Out, Mat] =
    new Source(delegate.takeWhile(p.test, inclusive))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[Source.limit]], [[Source.limitWeighted]]
   */
  def takeWhile(p: function.Predicate[Out]): javadsl.Source[Out, Mat] = new Source(delegate.takeWhile(p.test))

  /**
   * Discard elements at the beginning of the stream while predicate is true.
   * No elements will be dropped after predicate first time returned false.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' predicate returned false and for all following stream elements
   *
   * '''Backpressures when''' predicate returned false and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param p predicate is evaluated for each new element until first time returns false
   */
  def dropWhile(p: function.Predicate[Out]): javadsl.Source[Out, Mat] = new Source(delegate.dropWhile(p.test))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   */
  def take(n: Long): javadsl.Source[Out, Mat] =
    new Source(delegate.take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   *
   * '''Emits when''' an upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or timer fires
   *
   * '''Cancels when''' downstream cancels or timer fires
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def takeWithin(d: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.takeWithin(d))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   *
   * '''Emits when''' an upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or timer fires
   *
   * '''Cancels when''' downstream cancels or timer fires
   */
  @silent("deprecated")
  def takeWithin(d: java.time.Duration): javadsl.Source[Out, Mat] =
    takeWithin(d.asScala)

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate allows to derive a seed from the first element and change the aggregated type to be
   * different than the input type. See [[Flow.conflate]] for a simpler version that does not change types.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * see also [[Source.conflate]]  [[Source.batch]] [[Source.batchWeighted]]
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflateWithSeed[S](
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): javadsl.Source[S, Mat] =
    new Source(delegate.conflateWithSeed(seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   * This version of conflate does not change the output type of the stream. See [[Source.conflateWithSeed]] for a
   * more flexible version that can take a seed function and transform elements while rolling up.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * see also [[Source.conflateWithSeed]]  [[Source.batch]] [[Source.batchWeighted]]
   *
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate(aggregate: function.Function2[Out, Out, Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.conflate(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might store received elements in
   * an array up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is an aggregated element available
   *
   * '''Backpressures when''' there are `max` batched elements and 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[Source.conflate]], [[Source.batchWeighted]]
   *
   * @param max maximum number of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new aggregate
   */
  def batch[S](
      max: Long,
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): javadsl.Source[S, Mat] =
    new Source(delegate.batch(max, seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might concatenate `ByteString`
   * elements up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Batching will apply for all elements, even if a single element cost is greater than the total allowed limit.
   * In this case, previous batched elements will be emitted, then the "heavy" element will be emitted (after
   * being applied with the `seed` function) without batching further elements with it, and then the rest of the
   * incoming elements are batched.
   *
   * '''Emits when''' downstream stops backpressuring and there is a batched element available
   *
   * '''Backpressures when''' there are `max` weighted batched elements + 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[Source.conflate]], [[Source.batch]]
   *
   * @param max maximum weight of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param costFn a function to compute a single element weight
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new batch
   */
  def batchWeighted[S](
      max: Long,
      costFn: function.Function[Out, java.lang.Long],
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): javadsl.Source[S, Mat] =
    new Source(delegate.batchWeighted(max, costFn.apply, seed.apply)(aggregate.apply))

  /**
   * Allows a faster downstream to progress independently of a slower publisher by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * Expand does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `expander` function will complete the stream with failure.
   *
   * See also [[#extrapolate]] for a version that always preserves the original element and allows for an initial "startup" element.
   *
   * '''Emits when''' downstream stops backpressuring
   *
   * '''Backpressures when''' downstream backpressures or iterator runs empty
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param expander       Takes the current extrapolation state to produce an output element and the next extrapolation
   *                       state.
   * @see [[#extrapolate]]
   */
  def expand[U](expander: function.Function[Out, java.util.Iterator[U]]): javadsl.Source[U, Mat] =
    new Source(delegate.expand(in => expander(in).asScala))

  /**
   * Allows a faster downstream to progress independent of a slower upstream.
   *
   * This is achieved by introducing "extrapolated" elements - based on those from upstream - whenever downstream
   * signals demand.
   *
   * Extrapolate does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `extrapolate` function will complete the stream with failure.
   *
   * See also [[#expand]] for a version that can overwrite the original element.
   *
   * '''Emits when''' downstream stops backpressuring, AND EITHER upstream emits OR initial element is present OR
   * `extrapolate` is non-empty and applicable
   *
   * '''Backpressures when''' downstream backpressures or current `extrapolate` runs empty
   *
   * '''Completes when''' upstream completes and current `extrapolate` runs empty
   *
   * '''Cancels when''' downstream cancels
   *
   * @param extrapolator Takes the current upstream element and provides a sequence of "extrapolated" elements based
   *                    on the original, to be emitted in case downstream signals demand.
   * @see [[#expand]]
   */
  def extrapolate(extrapolator: function.Function[Out @uncheckedVariance, java.util.Iterator[Out @uncheckedVariance]])
      : Source[Out, Mat] =
    new Source(delegate.extrapolate(in => extrapolator(in).asScala))

  /**
   * Allows a faster downstream to progress independent of a slower upstream.
   *
   * This is achieved by introducing "extrapolated" elements - based on those from upstream - whenever downstream
   * signals demand.
   *
   * Extrapolate does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `extrapolate` function will complete the stream with failure.
   *
   * See also [[#expand]] for a version that can overwrite the original element.
   *
   * '''Emits when''' downstream stops backpressuring, AND EITHER upstream emits OR initial element is present OR
   * `extrapolate` is non-empty and applicable
   *
   * '''Backpressures when''' downstream backpressures or current `extrapolate` runs empty
   *
   * '''Completes when''' upstream completes and current `extrapolate` runs empty
   *
   * '''Cancels when''' downstream cancels
   *
   * @param extrapolator takes the current upstream element and provides a sequence of "extrapolated" elements based
   *                     on the original, to be emitted in case downstream signals demand.
   * @param initial      the initial element to be emitted, in case upstream is able to stall the entire stream.
   * @see [[#expand]]
   */
  def extrapolate(
      extrapolator: function.Function[Out @uncheckedVariance, java.util.Iterator[Out @uncheckedVariance]],
      initial: Out @uncheckedVariance): Source[Out, Mat] =
    new Source(delegate.extrapolate(in => extrapolator(in).asScala, Some(initial)))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * '''Emits when''' downstream stops backpressuring and there is a pending element in the buffer
   *
   * '''Backpressures when''' downstream backpressures or depending on OverflowStrategy:
   *  <ul>
   *    <li>Backpressure - backpressures when buffer is full</li>
   *    <li>DropHead, DropTail, DropBuffer - never backpressures</li>
   *    <li>Fail - fails the stream if buffer gets full</li>
   *  </ul>
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Source[Out, Mat] =
    new Source(delegate.buffer(size, overflowStrategy))

  /**
   * Takes up to `n` elements from the stream (less than `n` if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements has been seen, and therefore the substream
   *    has not yet been emitted
   *  - the tail substream signals the error after the prefix and tail has been emitted by the main stream
   *    (at that point the main stream has already completed)
   *
   * '''Emits when''' the configured number of prefix elements are available. Emits this prefix, and the rest
   * as a substream
   *
   * '''Backpressures when''' downstream backpressures or substream backpressures
   *
   * '''Completes when''' prefix elements has been consumed and substream has been consumed
   *
   * '''Cancels when''' downstream cancels or substream cancels
   */
  def prefixAndTail(n: Int): javadsl.Source[
    Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance, NotUsed]],
    Mat] =
    new Source(delegate.prefixAndTail(n).map { case (taken, tail) => Pair(taken.asJava, tail.asJava) })

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * a new substream is opened and subsequently fed with all elements belonging to
   * that key.
   *
   * WARNING: If `allowClosedSubstreamRecreation` is set to `false` (default behavior) the operator
   * keeps track of all keys of streams that have already been closed. If you expect an infinite
   * number of keys this can cause memory issues. Elements belonging to those keys are drained
   * directly and not send to the substream.
   *
   * Note: If `allowClosedSubstreamRecreation` is set to `true` substream completion and incoming
   * elements are subject to race-conditions. If elements arrive for a stream that is in the process
   * of closing these elements might get lost.
   *
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this operator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `groupBy`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * Function `f`  MUST NOT return `null`. This will throw exception and trigger supervision decision mechanism.
   *
   * '''Emits when''' an element for which the grouping function returns a group that has not yet been created.
   * Emits the new group
   *
   * '''Backpressures when''' there is an element pending for a group whose substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and all substreams cancel
   *
   * @param maxSubstreams configures the maximum number of substreams (keys)
   *        that are supported; if more distinct keys are encountered then the stream fails
   * @param f computes the key for each element
   * @param allowClosedSubstreamRecreation enables recreation of already closed substreams if elements with their
   *        corresponding keys arrive after completion
   */
  def groupBy[K](
      maxSubstreams: Int,
      f: function.Function[Out, K],
      allowClosedSubstreamRecreation: Boolean): SubSource[Out, Mat] =
    new SubSource(delegate.groupBy(maxSubstreams, f.apply, allowClosedSubstreamRecreation))

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * a new substream is opened and subsequently fed with all elements belonging to
   * that key.
   *
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubSource]]. This means that after this operator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubSource]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `groupBy`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' an element for which the grouping function returns a group that has not yet been created.
   * Emits the new group
   *
   * '''Backpressures when''' there is an element pending for a group whose substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and all substreams cancel
   *
   * @param maxSubstreams configures the maximum number of substreams (keys)
   *        that are supported; if more distinct keys are encountered then the stream fails
   */
  def groupBy[K](maxSubstreams: Int, f: function.Function[Out, K]): SubSource[Out @uncheckedVariance, Mat] =
    new SubSource(delegate.groupBy(maxSubstreams, f.apply))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it. This means
   * that for the following series of predicate values, three substreams will
   * be produced with lengths 1, 2, and 3:
   *
   * {{{
   * false,             // element goes into first substream
   * true, false,       // elements go into second substream
   * true, false, false // elements go into third substream
   * }}}
   *
   * In case the *first* element of the stream matches the predicate, the first
   * substream emitted by splitWhen will start from that element. For example:
   *
   * {{{
   * true, false, false // first substream starts from the split-by element
   * true, false        // subsequent substreams operate the same way
   * }}}
   *
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubSource]]. This means that after this operator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubSource]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `splitWhen`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element for which the provided predicate is true, opening and emitting a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel
   *
   * See also [[Source.splitAfter]].
   */
  def splitWhen(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.splitWhen(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it.
   *
   * @see [[#splitWhen]]
   */
  def splitWhen(substreamCancelStrategy: SubstreamCancelStrategy)(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.splitWhen(substreamCancelStrategy)(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true. This means that for the following series of predicate values,
   * three substreams will be produced with lengths 2, 2, and 3:
   *
   * {{{
   * false, true,        // elements go into first substream
   * false, true,        // elements go into second substream
   * false, false, true  // elements go into third substream
   * }}}
   *
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubSource]]. This means that after this operator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubSource]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `splitAfter`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element passes through. When the provided predicate is true it emits the element
   * and opens a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel
   *
   * See also [[Source.splitWhen]].
   */
  def splitAfter(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.splitAfter(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true.
   *
   * @see [[#splitAfter]]
   */
  def splitAfter(substreamCancelStrategy: SubstreamCancelStrategy)(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.splitAfter(substreamCancelStrategy)(p.test))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by concatenation,
   * fully consuming one Source after the other.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapConcat[T, M](f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): Source[T, Mat] =
    new Source(delegate.flatMapConcat[T, M](x => f(x)))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by merging, where at most `breadth`
   * substreams are being consumed at any given time.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapMerge[T, M](breadth: Int, f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): Source[T, Mat] =
    new Source(delegate.flatMapMerge(breadth, o => f(o)))

  /**
   * If the first element has not passed through this operator before the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def initialTimeout(timeout: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.initialTimeout(timeout))

  /**
   * If the first element has not passed through this operator before the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def initialTimeout(timeout: java.time.Duration): javadsl.Source[Out, Mat] =
    initialTimeout(timeout.asScala)

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def completionTimeout(timeout: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.completionTimeout(timeout))

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def completionTimeout(timeout: java.time.Duration): javadsl.Source[Out, Mat] =
    completionTimeout(timeout.asScala)

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between two emitted elements
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def idleTimeout(timeout: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.idleTimeout(timeout))

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between two emitted elements
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def idleTimeout(timeout: java.time.Duration): javadsl.Source[Out, Mat] =
    idleTimeout(timeout.asScala)

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between element emission and downstream demand.
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def backpressureTimeout(timeout: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.backpressureTimeout(timeout))

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between element emission and downstream demand.
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def backpressureTimeout(timeout: java.time.Duration): javadsl.Source[Out, Mat] =
    backpressureTimeout(timeout.asScala)

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * operator attempts to maintains a base rate of emitted elements towards the downstream.
   *
   * If the downstream backpressures then no element is injected until downstream demand arrives. Injected elements
   * do not accumulate during this period.
   *
   * Upstream elements are always preferred over injected elements.
   *
   * '''Emits when''' upstream emits an element or if the upstream was idle for the configured period
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def keepAlive(maxIdle: FiniteDuration, injectedElem: function.Creator[Out]): javadsl.Source[Out, Mat] =
    new Source(delegate.keepAlive(maxIdle, () => injectedElem.create()))

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * operator attempts to maintains a base rate of emitted elements towards the downstream.
   *
   * If the downstream backpressures then no element is injected until downstream demand arrives. Injected elements
   * do not accumulate during this period.
   *
   * Upstream elements are always preferred over injected elements.
   *
   * '''Emits when''' upstream emits an element or if the upstream was idle for the configured period
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def keepAlive(maxIdle: java.time.Duration, injectedElem: function.Creator[Out]): javadsl.Source[Out, Mat] =
    keepAlive(maxIdle.asScala, injectedElem)

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and
   * started.
   *
   * The burst size is calculated based on the given rate (`cost/per`) as 0.1 * rate, for example:
   * - rate < 20/second => burst size 1
   * - rate 20/second => burst size 2
   * - rate 100/second => burst size 10
   * - rate 200/second => burst size 20
   *
   * The throttle `mode` is [[akka.stream.ThrottleMode.Shaping]], which makes pauses before emitting messages to
   * meet throttle rate.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(elements: Int, per: java.time.Duration): javadsl.Source[Out, Mat] =
    new Source(delegate.throttle(elements, per.asScala))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int, mode: ThrottleMode): javadsl.Source[Out, Mat] =
    new Source(delegate.throttle(elements, per, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(
      elements: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      mode: ThrottleMode): javadsl.Source[Out, Mat] =
    new Source(delegate.throttle(elements, per.asScala, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and
   * started.
   *
   * The burst size is calculated based on the given rate (`cost/per`) as 0.1 * rate, for example:
   * - rate < 20/second => burst size 1
   * - rate 20/second => burst size 2
   * - rate 100/second => burst size 10
   * - rate 200/second => burst size 20
   *
   * The throttle `mode` is [[akka.stream.ThrottleMode.Shaping]], which makes pauses before emitting messages to
   * meet throttle rate.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      costCalculation: function.Function[Out, Integer]): javadsl.Source[Out, Mat] =
    new Source(delegate.throttle(cost, per.asScala, costCalculation.apply _))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def throttle(
      cost: Int,
      per: FiniteDuration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.Source[Out, Mat] =
    new Source(delegate.throttle(cost, per, maximumBurst, costCalculation.apply _, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.Source[Out, Mat] =
    new Source(delegate.throttle(cost, per.asScala, maximumBurst, costCalculation.apply _, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(elements: Int, per: FiniteDuration, mode: ThrottleMode): javadsl.Source[Out, Mat] =
    new Source(delegate.throttleEven(elements, per, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(elements: Int, per: java.time.Duration, mode: ThrottleMode): javadsl.Source[Out, Mat] =
    throttleEven(elements, per.asScala, mode)

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(
      cost: Int,
      per: FiniteDuration,
      costCalculation: (Out) => Int,
      mode: ThrottleMode): javadsl.Source[Out, Mat] =
    new Source(delegate.throttleEven(cost, per, costCalculation.apply _, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(
      cost: Int,
      per: java.time.Duration,
      costCalculation: (Out) => Int,
      mode: ThrottleMode): javadsl.Source[Out, Mat] =
    throttleEven(cost, per.asScala, costCalculation, mode)

  /**
   * Detaches upstream demand from downstream demand without detaching the
   * stream rates; in other words acts like a buffer of size 1.
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def detach: javadsl.Source[Out, Mat] = new Source(delegate.detach)

  /**
   * Materializes to `Future[Done]` that completes on getting termination message.
   * The Future completes with success when received complete message from upstream or cancel
   * from downstream. It fails with the same error when received error message from
   * downstream.
   */
  def watchTermination[M]()(matF: function.Function2[Mat, CompletionStage[Done], M]): javadsl.Source[Out, M] =
    new Source(delegate.watchTermination()((left, right) => matF(left, right.toJava)))

  /**
   * Materializes to `FlowMonitor<Out>` that allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   * The `combine` function is used to combine the `FlowMonitor` with this flow's materialized value.
   */
  @Deprecated
  @deprecated("Use monitor() or monitorMat(combine) instead", "2.5.17")
  def monitor[M]()(combine: function.Function2[Mat, FlowMonitor[Out], M]): javadsl.Source[Out, M] =
    new Source(delegate.monitorMat(combinerToScala(combine)))

  /**
   * Materializes to `FlowMonitor[Out]` that allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   * The `combine` function is used to combine the `FlowMonitor` with this flow's materialized value.
   */
  def monitorMat[M](combine: function.Function2[Mat, FlowMonitor[Out], M]): javadsl.Source[Out, M] =
    new Source(delegate.monitorMat(combinerToScala(combine)))

  /**
   * Materializes to `Pair<Mat, FlowMonitor<<Out>>`, which is unlike most other operators (!),
   * in which usually the default materialized value keeping semantics is to keep the left value
   * (by passing `Keep.left()` to a `*Mat` version of a method). This operator is an exception from
   * that rule and keeps both values since dropping its sole purpose is to introduce that materialized value.
   *
   * The `FlowMonitor` allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   */
  def monitor(): Source[Out, Pair[Mat, FlowMonitor[Out]]] =
    monitorMat(Keep.both)

  /**
   * Delays the initial element by the specified duration.
   *
   * '''Emits when''' upstream emits an element if the initial delay is already elapsed
   *
   * '''Backpressures when''' downstream backpressures or initial delay is not yet elapsed
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def initialDelay(delay: FiniteDuration): javadsl.Source[Out, Mat] =
    new Source(delegate.initialDelay(delay))

  /**
   * Delays the initial element by the specified duration.
   *
   * '''Emits when''' upstream emits an element if the initial delay is already elapsed
   *
   * '''Backpressures when''' downstream backpressures or initial delay is not yet elapsed
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @silent("deprecated")
  def initialDelay(delay: java.time.Duration): javadsl.Source[Out, Mat] =
    initialDelay(delay.asScala)

  /**
   * Replace the attributes of this [[Source]] with the given ones. If this Source is a composite
   * of multiple graphs, new attributes on the composite will be less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def withAttributes(attr: Attributes): javadsl.Source[Out, Mat] =
    new Source(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this [[Source]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this Source is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): javadsl.Source[Out, Mat] =
    new Source(delegate.addAttributes(attr))

  /**
   * Add a ``name`` attribute to this Source.
   */
  override def named(name: String): javadsl.Source[Out, Mat] =
    new Source(delegate.named(name))

  /**
   * Put an asynchronous boundary around this `Source`
   */
  override def async: javadsl.Source[Out, Mat] =
    new Source(delegate.async)

  /**
   * Put an asynchronous boundary around this `Source`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): javadsl.Source[Out, Mat] =
    new Source(delegate.async(dispatcher))

  /**
   * Put an asynchronous boundary around this `Source`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): javadsl.Source[Out, Mat] =
    new Source(delegate.async(dispatcher, inputBufferSize))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any], log: LoggingAdapter): javadsl.Source[Out, Mat] =
    new Source(delegate.log(name, e => extract.apply(e))(log))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any]): javadsl.Source[Out, Mat] =
    this.log(name, extract, null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, log: LoggingAdapter): javadsl.Source[Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String): javadsl.Source[Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

  def asSourceWithContext[Ctx](extractContext: function.Function[Out, Ctx]): SourceWithContext[Out, Ctx, Mat] =
    new scaladsl.SourceWithContext(this.asScala.map(x => (x, extractContext.apply(x)))).asJava
}
