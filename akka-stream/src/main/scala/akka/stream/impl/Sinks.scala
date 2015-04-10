/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import akka.actor.{ ActorRef, Props }
import akka.stream.impl.StreamLayout.Module
import akka.stream.OperationAttributes
import akka.stream.{ Inlet, Shape, SinkShape }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ Future, Promise }
import akka.stream.MaterializationContext
import akka.stream.ActorFlowMaterializer

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

  override def carbonCopy: Module = {
    val in = new Inlet[In](shape.inlet.toString)
    newInstance(SinkShape(in))
  }

  override def subModules: Set[Module] = Set.empty

  def amendShape(attr: OperationAttributes): SinkShape[In] = {
    attr.nameOption match {
      case None ⇒ shape
      case s: Some[String] if s == attributes.nameOption ⇒ shape
      case Some(name) ⇒ shape.copy(inlet = new Inlet(name + ".in"))
    }
  }
}

/**
 * INTERNAL API
 * Holds the downstream-most [[org.reactivestreams.Publisher]] interface of the materialized flow.
 * The stream will not have any subscribers attached at this point, which means that after prefetching
 * elements to fill the internal buffers it will assert back-pressure until
 * a subscriber connects and creates demand for elements to be emitted.
 */
private[akka] class PublisherSink[In](val attributes: OperationAttributes, shape: SinkShape[In]) extends SinkModule[In, Publisher[In]](shape) {

  override def toString: String = "PublisherSink"

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val pub = new VirtualPublisher[In]
    val sub = new VirtualSubscriber[In](pub)
    (sub, pub)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] = new PublisherSink[In](attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new PublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class FanoutPublisherSink[In](
  initialBufferSize: Int,
  maximumBufferSize: Int,
  val attributes: OperationAttributes,
  shape: SinkShape[In])
  extends SinkModule[In, Publisher[In]](shape) {

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val actorMaterializer = ActorFlowMaterializer.downcast(context.materializer)
    val fanoutActor = actorMaterializer.actorOf(context,
      Props(new FanoutProcessorImpl(actorMaterializer.effectiveSettings(context.effectiveAttributes),
        initialBufferSize, maximumBufferSize)))
    val fanoutProcessor = ActorProcessorFactory[In, In](fanoutActor)
    (fanoutProcessor, fanoutProcessor)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](initialBufferSize, maximumBufferSize, attributes, shape)

  override def withAttributes(attr: OperationAttributes): Module =
    new FanoutPublisherSink[In](initialBufferSize, maximumBufferSize, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] object HeadSink {
  class HeadSinkSubscriber[In](p: Promise[In]) extends Subscriber[In] {
    private val sub = new AtomicReference[Subscription]
    override def onSubscribe(s: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(s)
      if (!sub.compareAndSet(null, s)) s.cancel()
      else s.request(1)
    }

    override def onNext(elem: In): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(elem)
      p.trySuccess(elem)
      sub.get.cancel()
    }

    override def onError(t: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(t)
      p.tryFailure(t)
    }

    override def onComplete(): Unit = p.tryFailure(new NoSuchElementException("empty stream"))
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
private[akka] class HeadSink[In](val attributes: OperationAttributes, shape: SinkShape[In]) extends SinkModule[In, Future[In]](shape) {

  override def create(context: MaterializationContext) = {
    val p = Promise[In]()
    val sub = new HeadSink.HeadSinkSubscriber[In](p)
    (sub, p.future)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Future[In]] = new HeadSink[In](attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new HeadSink[In](attr, amendShape(attr))

  override def toString: String = "HeadSink"
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream which will just discard all received
 * elements.
 */
private[akka] final class BlackholeSink(val attributes: OperationAttributes, shape: SinkShape[Any]) extends SinkModule[Any, Unit](shape) {

  override def create(context: MaterializationContext) = {
    val effectiveSettings = ActorFlowMaterializer.downcast(context.materializer)
      .effectiveSettings(context.effectiveAttributes)
    (new BlackholeSubscriber[Any](effectiveSettings.maxInputBufferSize), ())
  }

  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Unit] = new BlackholeSink(attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new BlackholeSink(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream.
 */
private[akka] final class SubscriberSink[In](subscriber: Subscriber[In], val attributes: OperationAttributes, shape: SinkShape[In]) extends SinkModule[In, Unit](shape) {

  override def create(context: MaterializationContext) = (subscriber, ())

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Unit] = new SubscriberSink[In](subscriber, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new SubscriberSink[In](subscriber, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * A sink that immediately cancels its upstream upon materialization.
 */
private[akka] final class CancelSink(val attributes: OperationAttributes, shape: SinkShape[Any]) extends SinkModule[Any, Unit](shape) {

  override def create(context: MaterializationContext): (Subscriber[Any], Unit) = {
    val subscriber = new Subscriber[Any] {
      override def onError(t: Throwable): Unit = ()
      override def onSubscribe(s: Subscription): Unit = s.cancel()
      override def onComplete(): Unit = ()
      override def onNext(t: Any): Unit = ()
    }
    (subscriber, ())
  }

  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Unit] = new CancelSink(attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new CancelSink(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Subscriber]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorSubscriber]].
 */
private[akka] final class ActorSubscriberSink[In](props: Props, val attributes: OperationAttributes, shape: SinkShape[In]) extends SinkModule[In, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val subscriberRef = ActorFlowMaterializer.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, ActorRef] = new ActorSubscriberSink[In](props, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module = new ActorSubscriberSink[In](props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
private[akka] final class ActorRefSink[In](ref: ActorRef, onCompleteMessage: Any,
                                           val attributes: OperationAttributes,
                                           shape: SinkShape[In]) extends SinkModule[In, Unit](shape) {

  override def create(context: MaterializationContext) = {
    val actorMaterializer = ActorFlowMaterializer.downcast(context.materializer)
    val effectiveSettings = actorMaterializer.effectiveSettings(context.effectiveAttributes)
    val subscriberRef = actorMaterializer.actorOf(context,
      ActorRefSinkActor.props(ref, effectiveSettings.maxInputBufferSize, onCompleteMessage))
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), ())
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Unit] =
    new ActorRefSink[In](ref, onCompleteMessage, attributes, shape)
  override def withAttributes(attr: OperationAttributes): Module =
    new ActorRefSink[In](ref, onCompleteMessage, attr, amendShape(attr))
}

