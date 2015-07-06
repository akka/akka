/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.{ ActorRef, Cancellable, PoisonPill, Props }
import akka.stream.impl.StreamLayout.Module
import akka.stream._
import org.reactivestreams._
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Promise }
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 */
private[akka] abstract class SourceModule[+Out, +Mat](val shape: SourceShape[Out]) extends Module {

  def create(context: MaterializationContext): (Publisher[Out] @uncheckedVariance, Mat)

  override def replaceShape(s: Shape): Module =
    if (s == shape) this
    else throw new UnsupportedOperationException("cannot replace the shape of a Source, you need to wrap it in a Graph for that")

  // This is okay since the only caller of this method is right below.
  protected def newInstance(shape: SourceShape[Out] @uncheckedVariance): SourceModule[Out, Mat]

  override def carbonCopy: Module = newInstance(SourceShape(shape.outlet.carbonCopy()))

  override def subModules: Set[Module] = Set.empty

  protected def amendShape(attr: Attributes): SourceShape[Out] = {
    val thisN = attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(outlet = Outlet(thatN + ".out"))
  }
}

/**
 * INTERNAL API
 * Holds a `Subscriber` representing the input side of the flow.
 * The `Subscriber` can later be connected to an upstream `Publisher`.
 */
private[akka] final class SubscriberSource[Out](val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, Subscriber[Out]](shape) {

  override def create(context: MaterializationContext): (Publisher[Out], Subscriber[Out]) = {
    val processor = new VirtualProcessor[Out]
    (processor, processor)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Subscriber[Out]] = new SubscriberSource[Out](attributes, shape)
  override def withAttributes(attr: Attributes): Module = new SubscriberSource[Out](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
private[akka] final class PublisherSource[Out](p: Publisher[Out], val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, Unit](shape) {
  override def create(context: MaterializationContext) = (p, ())

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Unit] = new PublisherSource[Out](p, attributes, shape)
  override def withAttributes(attr: Attributes): Module = new PublisherSource[Out](p, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class LazyEmptySource[Out](val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, Promise[Unit]](shape) {
  import ReactiveStreamsCompliance._

  override def create(context: MaterializationContext) = {
    val p = Promise[Unit]()

    val pub = new Publisher[Unit] {
      override def subscribe(s: Subscriber[_ >: Unit]) = {
        requireNonNullSubscriber(s)
        tryOnSubscribe(s, new Subscription {
          override def request(n: Long): Unit = ()
          override def cancel(): Unit = p.trySuccess(())
        })
        p.future.onComplete {
          case Success(_)  ⇒ tryOnComplete(s)
          case Failure(ex) ⇒ tryOnError(s, ex) // due to external signal
        }(context.materializer.executionContext)
      }
    }

    pub.asInstanceOf[Publisher[Out]] → p
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Promise[Unit]] = new LazyEmptySource[Out](attributes, shape)
  override def withAttributes(attr: Attributes): Module = new LazyEmptySource(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Elements are emitted periodically with the specified interval.
 * The tick element will be delivered to downstream consumers that has requested any elements.
 * If a consumer has not requested any elements at the point in time when the tick
 * element is produced it will not receive that tick element later. It will
 * receive new tick elements as soon as it has requested more elements.
 */
private[akka] final class TickSource[Out](initialDelay: FiniteDuration, interval: FiniteDuration, tick: Out, val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, Cancellable](shape) {

  override def create(context: MaterializationContext) = {
    val cancelled = new AtomicBoolean(false)
    val actorMaterializer = ActorMaterializer.downcast(context.materializer)
    val effectiveSettings = actorMaterializer.effectiveSettings(context.effectiveAttributes)
    val ref = actorMaterializer.actorOf(context,
      TickPublisher.props(initialDelay, interval, tick, effectiveSettings, cancelled))
    (ActorPublisher[Out](ref), new Cancellable {
      override def cancel(): Boolean = {
        if (!isCancelled) ref ! PoisonPill
        true
      }
      override def isCancelled: Boolean = cancelled.get()
    })
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Cancellable] = new TickSource[Out](initialDelay, interval, tick, attributes, shape)
  override def withAttributes(attr: Attributes): Module = new TickSource(initialDelay, interval, tick, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Publisher]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorPublisher]].
 */
private[akka] final class ActorPublisherSource[Out](props: Props, val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val publisherRef = ActorMaterializer.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorPublisher[Out](publisherRef), publisherRef)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] = new ActorPublisherSource[Out](props, attributes, shape)
  override def withAttributes(attr: Attributes): Module = new ActorPublisherSource(props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class ActorRefSource[Out](
  bufferSize: Int, overflowStrategy: OverflowStrategy, val attributes: Attributes, shape: SourceShape[Out])
  extends SourceModule[Out, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val ref = ActorMaterializer.downcast(context.materializer).actorOf(context,
      ActorRefSourceActor.props(bufferSize, overflowStrategy))
    (akka.stream.actor.ActorPublisher[Out](ref), ref)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] =
    new ActorRefSource[Out](bufferSize, overflowStrategy, attributes, shape)
  override def withAttributes(attr: Attributes): Module =
    new ActorRefSource(bufferSize, overflowStrategy, attr, amendShape(attr))
}
