/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.NotUsed
import akka.actor._
import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import org.reactivestreams._
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.Promise
import akka.event.Logging

/**
 * INTERNAL API
 */
abstract class SourceModule[+Out, +Mat](val shape: SourceShape[Out]) extends AtomicModule {

  protected def label: String = Logging.simpleName(this)
  final override def toString: String = f"$label [${System.identityHashCode(this)}%08x]"

  def create(context: MaterializationContext): (Publisher[Out] @uncheckedVariance, Mat)

  override def replaceShape(s: Shape): AtomicModule =
    if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of a Source, you need to wrap it in a Graph for that")
    else this

  // This is okay since the only caller of this method is right below.
  protected def newInstance(shape: SourceShape[Out] @uncheckedVariance): SourceModule[Out, Mat]

  override def carbonCopy: AtomicModule = newInstance(SourceShape(shape.out.carbonCopy()))

  protected def amendShape(attr: Attributes): SourceShape[Out] = {
    val thisN = attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(out = Outlet(thatN + ".out"))
  }
}

/**
 * INTERNAL API
 * Holds a `Subscriber` representing the input side of the flow.
 * The `Subscriber` can later be connected to an upstream `Publisher`.
 */
final class SubscriberSource[Out](val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, Subscriber[Out]](shape) {

  override def create(context: MaterializationContext): (Publisher[Out], Subscriber[Out]) = {
    val processor = new VirtualProcessor[Out]
    (processor, processor)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Subscriber[Out]] = new SubscriberSource[Out](attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new SubscriberSource[Out](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
final class PublisherSource[Out](p: Publisher[Out], val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, NotUsed](shape) {

  override protected def label: String = s"PublisherSource($p)"

  override def create(context: MaterializationContext) = (p, NotUsed)

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, NotUsed] = new PublisherSource[Out](p, attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new PublisherSource[Out](p, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
final class MaybeSource[Out](val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, Promise[Option[Out]]](shape) {

  override def create(context: MaterializationContext) = {
    val p = Promise[Option[Out]]()
    new MaybePublisher[Out](p, attributes.nameOrDefault("MaybeSource"))(context.materializer.executionContext) → p
  }
  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Promise[Option[Out]]] = new MaybeSource[Out](attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new MaybeSource(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Publisher]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorPublisher]].
 */
final class ActorPublisherSource[Out](props: Props, val attributes: Attributes, shape: SourceShape[Out]) extends SourceModule[Out, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val publisherRef = ActorMaterializerHelper.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorPublisher[Out](publisherRef), publisherRef)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] =
    new ActorPublisherSource[Out](props, attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new ActorPublisherSource(props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
final class ActorRefSource[Out](
  bufferSize: Int, overflowStrategy: OverflowStrategy, val attributes: Attributes, shape: SourceShape[Out])
  extends SourceModule[Out, ActorRef](shape) {

  override protected def label: String = s"ActorRefSource($bufferSize, $overflowStrategy)"

  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializerHelper.downcast(context.materializer)
    val ref = mat.actorOf(context, ActorRefSourceActor.props(bufferSize, overflowStrategy, mat.settings))
    (akka.stream.actor.ActorPublisher[Out](ref), ref)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] =
    new ActorRefSource[Out](bufferSize, overflowStrategy, attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule =
    new ActorRefSource(bufferSize, overflowStrategy, attr, amendShape(attr))
}
