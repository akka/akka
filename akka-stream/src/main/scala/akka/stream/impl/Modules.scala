/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.stream._
import akka.stream.impl.AcknowledgePublisher.{ Ok, Rejected }
import akka.stream.impl.StreamLayout.Module
import akka.util.Timeout
import org.reactivestreams._

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ Future, Promise }
import scala.language.postfixOps
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

/**
 * INTERNAL API
 */
private[akka] final class AcknowledgeSource[Out](bufferSize: Int, overflowStrategy: OverflowStrategy,
                                                 val attributes: Attributes, shape: SourceShape[Out],
                                                 timeout: FiniteDuration = 5 seconds)
  extends SourceModule[Out, SourceQueue[Out]](shape) {

  override def create(context: MaterializationContext) = {
    import akka.pattern.ask
    val ref = ActorMaterializer.downcast(context.materializer).actorOf(context,
      AcknowledgePublisher.props(bufferSize, overflowStrategy))
    implicit val t = Timeout(timeout)

    (akka.stream.actor.ActorPublisher[Out](ref), new SourceQueue[Out] {
      implicit val ctx = context.materializer.executionContext
      override def offer(out: Out): Future[Boolean] = (ref ? out).map {
        case Ok()       ⇒ true
        case Rejected() ⇒ false
      }
    })
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, SourceQueue[Out]] =
    new AcknowledgeSource[Out](bufferSize, overflowStrategy, attributes, shape, timeout)
  override def withAttributes(attr: Attributes): Module =
    new AcknowledgeSource(bufferSize, overflowStrategy, attr, amendShape(attr), timeout)
}
