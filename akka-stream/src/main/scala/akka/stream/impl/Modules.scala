/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.NotUsed
import akka.actor._
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import org.reactivestreams._

import scala.annotation.unchecked.uncheckedVariance
import akka.event.Logging

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] abstract class SourceModule[+Out, +Mat](val shape: SourceShape[Out])
    extends AtomicModule[SourceShape[Out], Mat] {

  protected def label: String = Logging.simpleName(this)
  final override def toString: String = f"$label [${System.identityHashCode(this)}%08x]"

  def create(context: MaterializationContext): (Publisher[Out] @uncheckedVariance, Mat)

  // TODO: Remove this, no longer needed?
  protected def newInstance(shape: SourceShape[Out] @uncheckedVariance): SourceModule[Out, Mat]

  // TODO: Amendshape changed the name of ports. Is it needed anymore?

  def attributes: Attributes

  protected def amendShape(attr: Attributes): SourceShape[Out] = {
    val thisN = traversalBuilder.attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(out = Outlet(thatN + ".out"))
  }

  override private[stream] def traversalBuilder =
    LinearTraversalBuilder.fromModule(this, attributes).makeIsland(SourceModuleIslandTag)

}

/**
 * INTERNAL API
 * Holds a `Subscriber` representing the input side of the flow.
 * The `Subscriber` can later be connected to an upstream `Publisher`.
 */
@InternalApi private[akka] final class SubscriberSource[Out](val attributes: Attributes, shape: SourceShape[Out])
    extends SourceModule[Out, Subscriber[Out]](shape) {

  override def create(context: MaterializationContext): (Publisher[Out], Subscriber[Out]) = {
    val processor = new VirtualProcessor[Out]
    (processor, processor)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, Subscriber[Out]] =
    new SubscriberSource[Out](attributes, shape)
  override def withAttributes(attr: Attributes): SourceModule[Out, Subscriber[Out]] =
    new SubscriberSource[Out](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Construct a transformation starting with given publisher. The transformation steps
 * are executed by a series of [[org.reactivestreams.Processor]] instances
 * that mediate the flow of elements downstream and the propagation of
 * back-pressure upstream.
 */
@InternalApi private[akka] final class PublisherSource[Out](
    p: Publisher[Out],
    val attributes: Attributes,
    shape: SourceShape[Out])
    extends SourceModule[Out, NotUsed](shape) {

  override protected def label: String = s"PublisherSource($p)"

  override def create(context: MaterializationContext) = (p, NotUsed)

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, NotUsed] =
    new PublisherSource[Out](p, attributes, shape)
  override def withAttributes(attr: Attributes): SourceModule[Out, NotUsed] =
    new PublisherSource[Out](p, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Publisher]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorPublisher]].
 */
@InternalApi private[akka] final class ActorPublisherSource[Out](
    props: Props,
    val attributes: Attributes,
    shape: SourceShape[Out])
    extends SourceModule[Out, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val publisherRef = ActorMaterializerHelper.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorPublisher[Out](publisherRef), publisherRef)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] =
    new ActorPublisherSource[Out](props, attributes, shape)
  override def withAttributes(attr: Attributes): SourceModule[Out, ActorRef] =
    new ActorPublisherSource(props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorRefSource[Out](
    completionMatcher: PartialFunction[Any, Unit],
    failureMatcher: PartialFunction[Any, Throwable],
    bufferSize: Int,
    overflowStrategy: OverflowStrategy,
    val attributes: Attributes,
    shape: SourceShape[Out])
    extends SourceModule[Out, ActorRef](shape) {

  override protected def label: String = s"ActorRefSource($bufferSize, $overflowStrategy)"

  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializerHelper.downcast(context.materializer)
    val ref = mat.actorOf(
      context,
      ActorRefSourceActor.props(completionMatcher, failureMatcher, bufferSize, overflowStrategy, mat.settings))
    (akka.stream.actor.ActorPublisher[Out](ref), ref)
  }

  override protected def newInstance(shape: SourceShape[Out]): SourceModule[Out, ActorRef] =
    new ActorRefSource[Out](completionMatcher, failureMatcher, bufferSize, overflowStrategy, attributes, shape)
  override def withAttributes(attr: Attributes): SourceModule[Out, ActorRef] =
    new ActorRefSource(completionMatcher, failureMatcher, bufferSize, overflowStrategy, attr, amendShape(attr))
}
