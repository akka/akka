/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.impl._
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.impl.Ast.AstNode
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

sealed trait ActorFlowSource[+Out] extends Source[Out] {
  type MaterializedType

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
  def attach(flowSubscriber: Subscriber[Out] @uncheckedVariance, materializer: ActorBasedFlowMaterializer, flowName: String): MaterializedType

  /**
   * This method is only used for Sources that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Publisher[Out] @uncheckedVariance, MaterializedType) =
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

  override type Repr[+O] = SourcePipe[O]

  private def sourcePipe = Pipe.empty[Out].withSource(this)

  override def connect[T](flow: Flow[Out, T]): Source[T] = sourcePipe.connect(flow)

  override def connect(sink: Sink[Out]): RunnableFlow = sourcePipe.connect(sink)

  /** INTERNAL API */
  override private[scaladsl] def andThen[U](op: AstNode) = SourcePipe(this, List(op))
}

/**
 * A source that does not need to create a user-accessible object during materialization.
 */
trait SimpleActorFlowSource[+Out] extends ActorFlowSource[Out] {
  override type MaterializedType = Unit
}

/**
 * A source that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this source (could be a Subscriber, a
 * Future/Promise, etc.).
 */
trait KeyedActorFlowSource[+Out] extends ActorFlowSource[Out] with KeyedSource[Out]

/**
 * Holds a `Subscriber` representing the input side of the flow.
 * The `Subscriber` can later be connected to an upstream `Publisher`.
 */
private[scaladsl] final case class SubscriberSource[Out]() extends KeyedActorFlowSource[Out] {
  override type MaterializedType = Subscriber[Out]

  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Subscriber[Out] =
    flowSubscriber

}

/**
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
private[scaladsl] final case class PublisherSource[Out](p: Publisher[Out]) extends SimpleActorFlowSource[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) =
    p.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) = (p, ())
}

/**
 * Start a new `Source` from the given Iterator. The produced stream of elements
 * will continue until the iterator runs empty or fails during evaluation of
 * the `next()` method. Elements are pulled out of the iterator
 * in accordance with the demand coming from the downstream transformation
 * steps.
 */
private[scaladsl] final case class IteratorSource[Out](iterator: Iterator[Out]) extends SimpleActorFlowSource[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) =
    create(materializer, flowName)._1.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) =
    if (iterator.isEmpty) (EmptyPublisher[Out], ())
    else (ActorPublisher[Out](materializer.actorOf(IteratorPublisher.props(iterator, materializer.settings),
      name = s"$flowName-0-iterator")), ())
}

/**
 * Starts a new `Source` from the given `Iterable`. This is like starting from an
 * Iterator, but every Subscriber directly attached to the Publisher of this
 * stream will see an individual flow of elements (always starting from the
 * beginning) regardless of when they subscribed.
 */
private[scaladsl] final case class IterableSource[Out](iterable: immutable.Iterable[Out]) extends SimpleActorFlowSource[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) =
    create(materializer, flowName)._1.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) =
    if (iterable.isEmpty) (EmptyPublisher[Out], ())
    else (ActorPublisher[Out](materializer.actorOf(IterablePublisher.props(iterable, materializer.settings),
      name = s"$flowName-0-iterable")), ())
}

/**
 * Define the sequence of elements to be produced by the given closure.
 * The stream ends normally when evaluation of the closure returns a `None`.
 * The stream ends exceptionally when an exception is thrown from the closure.
 */
private[scaladsl] final case class ThunkSource[Out](f: () ⇒ Option[Out]) extends SimpleActorFlowSource[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) =
    create(materializer, flowName)._1.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) =
    (ActorPublisher[Out](materializer.actorOf(SimpleCallbackPublisher.props(materializer.settings,
      () ⇒ f() match {
        case Some(out) ⇒ out
        case _         ⇒ throw Stop
      }), name = s"$flowName-0-thunk")), ())
}

/**
 * Start a new `Source` from the given `Future`. The stream will consist of
 * one element when the `Future` is completed with a successful value, which
 * may happen before or after materializing the `Flow`.
 * The stream terminates with an error if the `Future` is completed with a failure.
 */
private[scaladsl] final case class FutureSource[Out](future: Future[Out]) extends SimpleActorFlowSource[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) =
    create(materializer, flowName)._1.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) =
    future.value match {
      case Some(Success(element)) ⇒
        (ActorPublisher[Out](materializer.actorOf(IterablePublisher.props(List(element), materializer.settings),
          name = s"$flowName-0-future")), ())
      case Some(Failure(t)) ⇒
        (ErrorPublisher(t).asInstanceOf[Publisher[Out]], ())
      case None ⇒
        (ActorPublisher[Out](materializer.actorOf(FuturePublisher.props(future, materializer.settings),
          name = s"$flowName-0-future")), ())
    }
}

/**
 * Elements are produced from the tick closure periodically with the specified interval.
 * The tick element will be delivered to downstream consumers that has requested any elements.
 * If a consumer has not requested any elements at the point in time when the tick
 * element is produced it will not receive that tick element later. It will
 * receive new tick elements as soon as it has requested more elements.
 */
private[scaladsl] final case class TickSource[Out](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ Out) extends SimpleActorFlowSource[Out] {
  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) =
    create(materializer, flowName)._1.subscribe(flowSubscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) =
    (ActorPublisher[Out](materializer.actorOf(TickPublisher.props(initialDelay, interval, tick, materializer.settings),
      name = s"$flowName-0-tick")), ())
}

/**
 * This Source takes two Sources and concatenates them together by draining the elements coming from the first Source
 * completely, then draining the elements arriving from the second Source. If the first Source is infinite then the
 * second Source will be never drained.
 */
private[scaladsl] final case class ConcatSource[Out](source1: Source[Out], source2: Source[Out]) extends SimpleActorFlowSource[Out] {

  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val concatter = Concat[Out]
    val concatGraph = FlowGraph { builder ⇒
      builder
        .addEdge(source1, Pipe.empty[Out], concatter.first)
        .addEdge(source2, Pipe.empty[Out], concatter.second)
        .addEdge(concatter.out, Sink(flowSubscriber))
    }.run()(materializer)
  }

  override def isActive: Boolean = false
}

/**
 * Creates and wraps an actor into [[org.reactivestreams.Publisher]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorPublisher]].
 */
private[scaladsl] final case class PropsSource[Out](props: Props) extends KeyedActorFlowSource[Out] {
  override type MaterializedType = ActorRef

  override def attach(flowSubscriber: Subscriber[Out], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val (publisher, publisherRef) = create(materializer, flowName)
    publisher.subscribe(flowSubscriber)
    publisherRef
  }
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val publisherRef = materializer.actorOf(props, name = s"$flowName-0-props")
    (akka.stream.actor.ActorPublisher[Out](publisherRef), publisherRef)
  }
}
