/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.impl._
import akka.stream.impl2.ActorBasedFlowMaterializer
import akka.stream.{ Transformer, OverflowStrategy, TimerTransformer }
import org.reactivestreams.{ Publisher, Subscriber }
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.util.{ Failure, Success }

import scala.annotation.unchecked.uncheckedVariance

/**
 * This trait is a marker for a pluggable stream tap. Concrete instances should
 * implement [[TapWithKey]] or [[SimpleTap]], otherwise a custom [[FlowMaterializer]]
 * will have to be used to be able to attach them.
 *
 * All Taps defined in this package rely upon an ActorBasedFlowMaterializer being
 * made available to them in order to use the <code>attach</code> method. Other
 * FlowMaterializers can be used but must then implement the functionality of these
 * Tap nodes themselves (or construct an ActorBasedFlowMaterializer).
 */
trait Tap[+Out] extends Source[Out]

/**
 * A tap that does not need to create a user-accessible object during materialization.
 */
trait SimpleTap[+Out] extends Tap[Out] with TapOps[Out] {
  /**
   * Attach this tap to the given [[org.reactivestreams.Subscriber]]. Using the given
   * [[FlowMaterializer]] is completely optional, especially if this tap belongs to
   * a different Reactive Streams implementation. It is the responsibility of the
   * caller to provide a suitable FlowMaterializer that can be used for running
   * Flows if necessary.
   *
   * @param flowSubscriber the Subscriber to produce elements to
   * @param materializer a FlowMaterializer that may be used for creating flows
   * @param flowName the name of the current flow, which should be used in log statements or error messages
   */
  def attach(flowSubscriber: Subscriber[Out] @uncheckedVariance, materializer: ActorBasedFlowMaterializer, flowName: String): Unit
  /**
   * This method is only used for Taps that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] @uncheckedVariance =
    throw new UnsupportedOperationException(s"forgot to implement create() for $getClass that says isActive==true")
  /**
   * This method indicates whether this Tap can create a Publisher instead of being
   * attached to a Subscriber. This is only used if the Flow does not contain any
   * operations.
   */
  def isActive: Boolean = false

  // these are unique keys, case class equality would break them
  final override def equals(other: Any): Boolean = super.equals(other)
  final override def hashCode: Int = super.hashCode
}

/**
 * A tap that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this tap (could be a Subscriber, a
 * Future/Promise, etc.).
 */
trait TapWithKey[+Out, T] extends TapOps[Out] {
  /**
   * Attach this tap to the given [[org.reactivestreams.Subscriber]]. Using the given
   * [[FlowMaterializer]] is completely optional, especially if this tap belongs to
   * a different Reactive Streams implementation. It is the responsibility of the
   * caller to provide a suitable FlowMaterializer that can be used for running
   * Flows if necessary.
   *
   * @param flowSubscriber the Subscriber to produce elements to
   * @param materializer a FlowMaterializer that may be used for creating flows
   * @param flowName the name of the current flow, which should be used in log statements or error messages
   */
  def attach(flowSubscriber: Subscriber[Out] @uncheckedVariance, materializer: ActorBasedFlowMaterializer, flowName: String): T
  /**
   * This method is only used for Taps that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Publisher[Out] @uncheckedVariance, T) =
    throw new UnsupportedOperationException(s"forgot to implement create() for $getClass that says isActive==true")
  /**
   * This method indicates whether this Tap can create a Publisher instead of being
   * attached to a Subscriber. This is only used if the Flow does not contain any
   * operations.
   */
  def isActive: Boolean = false

  // these are unique keys, case class equality would break them
  final override def equals(other: Any): Boolean = super.equals(other)
  final override def hashCode: Int = super.hashCode
}

/**
 * Holds a `Subscriber` representing the input side of the flow.
 * The `Subscriber` can later be connected to an upstream `Publisher`.
 */
final case class SubscriberTap[Out]() extends TapWithKey[Out, Subscriber[Out]] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Subscriber[Out] =
    flowSubscriber

  def subscriber(m: MaterializedTap): Subscriber[Out] = m.getTapFor(this)
}

/**
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
final case class PublisherTap[Out](p: Publisher[Out]) extends SimpleTap[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    p.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] = p
}

/**
 * Start a new `Source` from the given Iterator. The produced stream of elements
 * will continue until the iterator runs empty or fails during evaluation of
 * the `next()` method. Elements are pulled out of the iterator
 * in accordance with the demand coming from the downstream transformation
 * steps.
 */
final case class IteratorTap[Out](iterator: Iterator[Out]) extends SimpleTap[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] =
    if (iterator.isEmpty) EmptyPublisher[Out]
    else ActorPublisher[Out](materializer.actorOf(IteratorPublisher.props(iterator, materializer.settings),
      name = s"$flowName-0-iterator"))
}

/**
 * Starts a new `Source` from the given `Iterable`. This is like starting from an
 * Iterator, but every Subscriber directly attached to the Publisher of this
 * stream will see an individual flow of elements (always starting from the
 * beginning) regardless of when they subscribed.
 */
final case class IterableTap[Out](iterable: immutable.Iterable[Out]) extends SimpleTap[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] =
    if (iterable.isEmpty) EmptyPublisher[Out]
    else ActorPublisher[Out](materializer.actorOf(IterablePublisher.props(iterable, materializer.settings),
      name = s"$flowName-0-iterable"), Some(iterable))
}

/**
 * Define the sequence of elements to be produced by the given closure.
 * The stream ends normally when evaluation of the closure returns a `None`.
 * The stream ends exceptionally when an exception is thrown from the closure.
 */
final case class ThunkTap[Out](f: () ⇒ Option[Out]) extends SimpleTap[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] =
    ActorPublisher[Out](materializer.actorOf(SimpleCallbackPublisher.props(materializer.settings,
      () ⇒ f().getOrElse(throw Stop)), name = s"$flowName-0-thunk"))
}

/**
 * Start a new `Source` from the given `Future`. The stream will consist of
 * one element when the `Future` is completed with a successful value, which
 * may happen before or after materializing the `Flow`.
 * The stream terminates with an error if the `Future` is completed with a failure.
 */
final case class FutureTap[Out](future: Future[Out]) extends SimpleTap[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] =
    future.value match {
      case Some(Success(element)) ⇒
        ActorPublisher[Out](materializer.actorOf(IterablePublisher.props(List(element), materializer.settings),
          name = s"$flowName-0-future"), Some(future))
      case Some(Failure(t)) ⇒
        ErrorPublisher(t).asInstanceOf[Publisher[Out]]
      case None ⇒
        ActorPublisher[Out](materializer.actorOf(FuturePublisher.props(future, materializer.settings),
          name = s"$flowName-0-future"), Some(future))
    }
}

/**
 * Elements are produced from the tick closure periodically with the specified interval.
 * The tick element will be delivered to downstream consumers that has requested any elements.
 * If a consumer has not requested any elements at the point in time when the tick
 * element is produced it will not receive that tick element later. It will
 * receive new tick elements as soon as it has requested more elements.
 */
final case class TickTap[Out](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ Out) extends SimpleTap[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] =
    ActorPublisher[Out](materializer.actorOf(TickPublisher.props(initialDelay, interval, tick, materializer.settings),
      name = s"$flowName-0-tick"))
}

trait MaterializedTap {
  /**
   * Do not call directly. Use accessor method in the concrete `Tap`, e.g. [[SubscriberTap#subscriber]].
   */
  def getTapFor[T](tapKey: TapWithKey[_, T]): T
}

trait TapOps[+Out] extends Tap[Out] {
  override type Repr[+O] = SourcePipe[O]

  private def sourcePipe = Pipe.empty[Out].withTap(this)

  override def connect[T](flow: Flow[Out, T]): Source[T] = sourcePipe.connect(flow)

  override def connect(sink: Sink[Out]): RunnableFlow = sourcePipe.connect(sink)

  override def toPublisher()(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] =
    sourcePipe.toPublisher()(materializer)

  override def toFanoutPublisher(initialBufferSize: Int, maximumBufferSize: Int)(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] =
    sourcePipe.toFanoutPublisher(initialBufferSize, maximumBufferSize)(materializer)

  override def publishTo(subscriber: Subscriber[Out @uncheckedVariance])(implicit materializer: FlowMaterializer): Unit =
    sourcePipe.publishTo(subscriber)(materializer)

  override def consume()(implicit materializer: FlowMaterializer): Unit =
    sourcePipe.consume()

  override def map[T](f: Out ⇒ T): Repr[T] = sourcePipe.map(f)

  override def mapConcat[T](f: Out ⇒ immutable.Seq[T]): Repr[T] = sourcePipe.mapConcat(f)

  override def mapAsync[T](f: Out ⇒ Future[T]): Repr[T] = sourcePipe.mapAsync(f)

  override def mapAsyncUnordered[T](f: Out ⇒ Future[T]): Repr[T] = sourcePipe.mapAsyncUnordered(f)

  override def filter(p: Out ⇒ Boolean): Repr[Out] = sourcePipe.filter(p)

  override def collect[T](pf: PartialFunction[Out, T]): Repr[T] = sourcePipe.collect(pf)

  override def grouped(n: Int): Repr[immutable.Seq[Out]] = sourcePipe.grouped(n)

  override def groupedWithin(n: Int, d: FiniteDuration): Repr[immutable.Seq[Out]] = sourcePipe.groupedWithin(n, d)

  override def drop(n: Int): Repr[Out] = sourcePipe.drop(n)

  override def dropWithin(d: FiniteDuration): Repr[Out] = sourcePipe.dropWithin(d)

  override def take(n: Int): Repr[Out] = sourcePipe.take(n)

  override def takeWithin(d: FiniteDuration): Repr[Out] = sourcePipe.takeWithin(d)

  override def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Repr[S] = sourcePipe.conflate(seed, aggregate)

  override def expand[S, U](seed: Out ⇒ S, extrapolate: S ⇒ (U, S)): Repr[U] = sourcePipe.expand(seed, extrapolate)

  override def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[Out] = sourcePipe.buffer(size, overflowStrategy)

  override def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[T] = sourcePipe.transform(name, mkTransformer)

  override def prefixAndTail[U >: Out](n: Int): Repr[(immutable.Seq[Out], Source[U])] = sourcePipe.prefixAndTail(n)

  override def groupBy[K, U >: Out](f: Out ⇒ K): Repr[(K, Source[U])] = sourcePipe.groupBy(f)

  override def splitWhen[U >: Out](p: Out ⇒ Boolean): Repr[Source[U]] = sourcePipe.splitWhen(p)

  override def flatten[U](strategy: FlattenStrategy[Out, U]): Repr[U] = sourcePipe.flatten(strategy)

  override def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[Out, U]): Repr[U] =
    sourcePipe.timerTransform(name, mkTransformer)
}
