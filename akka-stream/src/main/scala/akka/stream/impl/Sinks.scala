/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Deploy, ActorRef, Props }
import akka.stream.impl.StreamLayout.Module
import akka.stream.{ Attributes, Inlet, Shape, SinkShape, MaterializationContext, ActorMaterializer }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 */
private[akka] abstract class SinkModule[-In, Mat](val shape: SinkShape[In]) extends Module {

  def create(context: MaterializationContext): (Subscriber[In] @uncheckedVariance, Mat)

  override def replaceShape(s: Shape): Module =
    if (s == shape) this
    else throw new UnsupportedOperationException("cannot replace the shape of a Sink, you need to wrap it in a Graph for that")

  // This is okay since we the only caller of this method is right below.
  protected def newInstance(s: SinkShape[In] @uncheckedVariance): SinkModule[In, Mat]

  override def carbonCopy: Module = newInstance(SinkShape(shape.inlet.carbonCopy()))

  override def subModules: Set[Module] = Set.empty

  protected def amendShape(attr: Attributes): SinkShape[In] = {
    val thisN = attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(inlet = Inlet(thatN + ".in"))
  }
}

/**
 * INTERNAL API
 * Holds the downstream-most [[org.reactivestreams.Publisher]] interface of the materialized flow.
 * The stream will not have any subscribers attached at this point, which means that after prefetching
 * elements to fill the internal buffers it will assert back-pressure until
 * a subscriber connects and creates demand for elements to be emitted.
 */
private[akka] class PublisherSink[In](val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, Publisher[In]](shape) {

  override def toString: String = "PublisherSink"

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val proc = new VirtualProcessor[In]
    (proc, proc)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] = new PublisherSink[In](attributes, shape)
  override def withAttributes(attr: Attributes): Module = new PublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class FanoutPublisherSink[In](
  initialBufferSize: Int,
  maximumBufferSize: Int,
  val attributes: Attributes,
  shape: SinkShape[In])
  extends SinkModule[In, Publisher[In]](shape) {

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val actorMaterializer = ActorMaterializer.downcast(context.materializer)
    val fanoutActor = actorMaterializer.actorOf(context,
      Props(new FanoutProcessorImpl(actorMaterializer.effectiveSettings(context.effectiveAttributes),
        initialBufferSize, maximumBufferSize)).withDeploy(Deploy.local))
    val fanoutProcessor = ActorProcessorFactory[In, In](fanoutActor)
    (fanoutProcessor, fanoutProcessor)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](initialBufferSize, maximumBufferSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new FanoutPublisherSink[In](initialBufferSize, maximumBufferSize, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] object HeadSink {
  final class HeadSinkSubscriber[In] extends Subscriber[In] {
    private[this] var subscription: Subscription = null
    private[this] val promise: Promise[In] = Promise[In]()
    def future: Future[In] = promise.future
    override def onSubscribe(s: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(s)
      if (subscription ne null) s.cancel()
      else {
        subscription = s
        s.request(1)
      }
    }

    override def onNext(elem: In): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      promise.trySuccess(elem)
      subscription.cancel()
      subscription = null
    }

    override def onError(t: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(t)
      promise.tryFailure(t)
    }

    override def onComplete(): Unit =
      promise.tryFailure(new NoSuchElementException("empty stream"))
  }

}

/**
 * INTERNAL API
 * Holds a [[scala.concurrent.Future]] that will be fulfilled with the first
 * thing that is signaled to this stream, which can be either an element (after
 * which the upstream subscription is canceled), an error condition (putting
 * the Future into the corresponding failed state) or the end-of-stream
 * (failing the Future with a NoSuchElementException).
 */
private[akka] final class HeadSink[In](val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, Future[In]](shape) {
  override def create(context: MaterializationContext) = {
    val sub = new HeadSink.HeadSinkSubscriber[In]
    (sub, sub.future)
  }
  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Future[In]] = new HeadSink[In](attributes, shape)
  override def withAttributes(attr: Attributes): Module = new HeadSink[In](attr, amendShape(attr))
  override def toString: String = "HeadSink"
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream which will just discard all received
 * elements.
 */
private[akka] final class BlackholeSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, Future[Unit]](shape) {

  override def create(context: MaterializationContext) = {
    val effectiveSettings = ActorMaterializer.downcast(context.materializer).effectiveSettings(context.effectiveAttributes)
    val p = Promise[Unit]()
    (new BlackholeSubscriber[Any](effectiveSettings.maxInputBufferSize, p), p.future)
  }

  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Future[Unit]] = new BlackholeSink(attributes, shape)
  override def withAttributes(attr: Attributes): Module = new BlackholeSink(attr, amendShape(attr))
  override def toString: String = "BlackholeSink"
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream.
 */
private[akka] final class SubscriberSink[In](subscriber: Subscriber[In], val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, Unit](shape) {

  override def create(context: MaterializationContext) = (subscriber, ())

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Unit] = new SubscriberSink[In](subscriber, attributes, shape)
  override def withAttributes(attr: Attributes): Module = new SubscriberSink[In](subscriber, attr, amendShape(attr))
  override def toString: String = "SubscriberSink"
}

/**
 * INTERNAL API
 * A sink that immediately cancels its upstream upon materialization.
 */
private[akka] final class CancelSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, Unit](shape) {
  override def create(context: MaterializationContext): (Subscriber[Any], Unit) = (new CancellingSubscriber[Any], ())
  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Unit] = new CancelSink(attributes, shape)
  override def withAttributes(attr: Attributes): Module = new CancelSink(attr, amendShape(attr))
  override def toString: String = "CancelSink"
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Subscriber]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorSubscriber]].
 */
private[akka] final class ActorSubscriberSink[In](props: Props, val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val subscriberRef = ActorMaterializer.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, ActorRef] = new ActorSubscriberSink[In](props, attributes, shape)
  override def withAttributes(attr: Attributes): Module = new ActorSubscriberSink[In](props, attr, amendShape(attr))
  override def toString: String = "ActorSubscriberSink"
}

/**
 * INTERNAL API
 */
private[akka] final class ActorRefSink[In](ref: ActorRef, onCompleteMessage: Any,
                                           val attributes: Attributes,
                                           shape: SinkShape[In]) extends SinkModule[In, Unit](shape) {

  override def create(context: MaterializationContext) = {
    val actorMaterializer = ActorMaterializer.downcast(context.materializer)
    val effectiveSettings = actorMaterializer.effectiveSettings(context.effectiveAttributes)
    val subscriberRef = actorMaterializer.actorOf(context,
      ActorRefSinkActor.props(ref, effectiveSettings.maxInputBufferSize, onCompleteMessage))
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), ())
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Unit] =
    new ActorRefSink[In](ref, onCompleteMessage, attributes, shape)
  override def withAttributes(attr: Attributes): Module =
    new ActorRefSink[In](ref, onCompleteMessage, attr, amendShape(attr))
  override def toString: String = "ActorRefSink"
}

