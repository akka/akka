/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

import org.reactivestreams.{ Publisher, Subscriber }

import akka.stream.impl.{ ActorPublisher, EmptyPublisher, ErrorPublisher, FuturePublisher, IterablePublisher, IteratorPublisher, SimpleCallbackPublisher, TickPublisher, Stop }
import akka.stream.impl2.ActorBasedFlowMaterializer

object FlowFrom {
  /**
   * Helper to create `Flow` without [[Source]].
   * Example usage: `FlowFrom[Int]`
   */
  def apply[T]: ProcessorFlow[T, T] = ProcessorFlow.empty[T]

  /**
   * Helper to create `Flow` with [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](publisher: Publisher[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(PublisherSource(publisher))

  /**
   * Helper to create `Flow` with [[Source]] from `Iterator`.
   * Example usage: `FlowFrom(Seq(1,2,3).iterator)`
   *
   * Start a new `Flow` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def apply[T](iterator: Iterator[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(IteratorSource(iterator))

  /**
   * Helper to create `Flow` with [[Source]] from `Iterable`.
   * Example usage: `FlowFrom(Seq(1,2,3))`
   *
   * Starts a new `Flow` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(IterableSource(iterable))

  /**
   * Define the sequence of elements to be produced by the given closure.
   * The stream ends normally when evaluation of the closure returns a `None`.
   * The stream ends exceptionally when an exception is thrown from the closure.
   */
  def apply[T](f: () ⇒ Option[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(ThunkSource(f))

  /**
   * Start a new `Flow` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(FutureSource(future))

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ T): FlowWithSource[T, T] =
    FlowFrom[T].withSource(TickSource(initialDelay, interval, tick))

}

/**
 * This trait is a marker for a pluggable stream source. Concrete instances should
 * implement [[SourceWithKey]] or [[SimpleSource]], otherwise a custom [[FlowMaterializer]]
 * will have to be used to be able to attach them.
 *
 * All Sources defined in this package rely upon an ActorBasedFlowMaterializer being
 * made available to them in order to use the <code>attach</code> method. Other
 * FlowMaterializers can be used but must then implement the functionality of these
 * Source nodes themselves (or construct an ActorBasedFlowMaterializer).
 */
trait Source[+In]

/**
 * A source that does not need to create a user-accessible object during materialization.
 */
trait SimpleSource[+In] extends Source[In] {
  /**
   * Attach this source to the given [[org.reactivestreams.Subscriber]]. Using the given
   * [[FlowMaterializer]] is completely optional, especially if this source belongs to
   * a different Reactive Streams implementation. It is the responsibility of the
   * caller to provide a suitable FlowMaterializer that can be used for running
   * Flows if necessary.
   *
   * @param flowSubscriber the Subscriber to produce elements to
   * @param materializer a FlowMaterializer that may be used for creating flows
   * @param flowName the name of the current flow, which should be used in log statements or error messages
   */
  def attach(flowSubscriber: Subscriber[In] @uncheckedVariance, materializer: ActorBasedFlowMaterializer, flowName: String): Unit
  /**
   * This method is only used for Sources that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] @uncheckedVariance =
    throw new UnsupportedOperationException(s"forgot to implement create() for $getClass that says isActive==true")
  /**
   * This method indicates whether this Source can create a Publisher instead of being
   * attached to a Subscriber. This is only used if the Flow does not contain any
   * operations.
   */
  def isActive: Boolean = false

  // these are unique keys, case class equality would break them
  final override def equals(other: Any): Boolean = super.equals(other)
  final override def hashCode: Int = super.hashCode
}

/**
 * A source that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this source (could be a Subscriber, a
 * Future/Promise, etc.).
 */
trait SourceWithKey[+In, T] extends Source[In] {
  /**
   * Attach this source to the given [[org.reactivestreams.Subscriber]]. Using the given
   * [[FlowMaterializer]] is completely optional, especially if this source belongs to
   * a different Reactive Streams implementation. It is the responsibility of the
   * caller to provide a suitable FlowMaterializer that can be used for running
   * Flows if necessary.
   *
   * @param flowSubscriber the Subscriber to produce elements to
   * @param materializer a FlowMaterializer that may be used for creating flows
   * @param flowName the name of the current flow, which should be used in log statements or error messages
   */
  def attach(flowSubscriber: Subscriber[In] @uncheckedVariance, materializer: ActorBasedFlowMaterializer, flowName: String): T
  /**
   * This method is only used for Sources that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Publisher[In] @uncheckedVariance, T) =
    throw new UnsupportedOperationException(s"forgot to implement create() for $getClass that says isActive==true")
  /**
   * This method indicates whether this Source can create a Publisher instead of being
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
final case class SubscriberSource[In]() extends SourceWithKey[In, Subscriber[In]] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Subscriber[In] =
    flowSubscriber

  def subscriber(m: MaterializedSource): Subscriber[In] = m.getSourceFor(this)
}

/**
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
final case class PublisherSource[In](p: Publisher[In]) extends SimpleSource[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    p.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] = p
}

/**
 * Start a new `Flow` from the given Iterator. The produced stream of elements
 * will continue until the iterator runs empty or fails during evaluation of
 * the `next()` method. Elements are pulled out of the iterator
 * in accordance with the demand coming from the downstream transformation
 * steps.
 */
final case class IteratorSource[In](iterator: Iterator[In]) extends SimpleSource[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] =
    if (iterator.isEmpty) EmptyPublisher[In]
    else ActorPublisher[In](materializer.actorOf(IteratorPublisher.props(iterator, materializer.settings),
      name = s"$flowName-0-iterator"))
}

/**
 * Starts a new `Flow` from the given `Iterable`. This is like starting from an
 * Iterator, but every Subscriber directly attached to the Publisher of this
 * stream will see an individual flow of elements (always starting from the
 * beginning) regardless of when they subscribed.
 */
final case class IterableSource[In](iterable: immutable.Iterable[In]) extends SimpleSource[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] =
    if (iterable.isEmpty) EmptyPublisher[In]
    else ActorPublisher[In](materializer.actorOf(IterablePublisher.props(iterable, materializer.settings),
      name = s"$flowName-0-iterable"), Some(iterable))
}

/**
 * Define the sequence of elements to be produced by the given closure.
 * The stream ends normally when evaluation of the closure returns a `None`.
 * The stream ends exceptionally when an exception is thrown from the closure.
 */
final case class ThunkSource[In](f: () ⇒ Option[In]) extends SimpleSource[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] =
    ActorPublisher[In](materializer.actorOf(SimpleCallbackPublisher.props(materializer.settings,
      () ⇒ f().getOrElse(throw Stop)), name = s"$flowName-0-thunk"))
}

/**
 * Start a new `Flow` from the given `Future`. The stream will consist of
 * one element when the `Future` is completed with a successful value, which
 * may happen before or after materializing the `Flow`.
 * The stream terminates with an error if the `Future` is completed with a failure.
 */
final case class FutureSource[In](future: Future[In]) extends SimpleSource[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] =
    future.value match {
      case Some(Success(element)) ⇒
        ActorPublisher[In](materializer.actorOf(IterablePublisher.props(List(element), materializer.settings),
          name = s"$flowName-0-future"), Some(future))
      case Some(Failure(t)) ⇒
        ErrorPublisher(t).asInstanceOf[Publisher[In]]
      case None ⇒
        ActorPublisher[In](materializer.actorOf(FuturePublisher.props(future, materializer.settings),
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
final case class TickSource[In](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ In) extends SimpleSource[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    create(materializer, flowName).subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[In] =
    ActorPublisher[In](materializer.actorOf(TickPublisher.props(initialDelay, interval, tick, materializer.settings),
      name = s"$flowName-0-tick"))
}

