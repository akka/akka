/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ ActorRef, Props }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.stage.{ AsyncCallback, GraphStageLogic, GraphStageWithMaterializedValue, InHandler }
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ Future, Promise }
import scala.language.postfixOps
import scala.util.{ Failure, Success, Try }

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
  val attributes: Attributes,
  shape: SinkShape[In])
  extends SinkModule[In, Publisher[In]](shape) {

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val actorMaterializer = ActorMaterializer.downcast(context.materializer)
    val fanoutProcessor = ActorProcessorFactory[In, In](
      actorMaterializer.actorOf(
        context,
        FanoutProcessorImpl.props(actorMaterializer.effectiveSettings(attributes))))
    (fanoutProcessor, fanoutProcessor)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new FanoutPublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream which will just discard all received
 * elements.
 */
private[akka] final class SinkholeSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, Future[Unit]](shape) {

  override def create(context: MaterializationContext) = {
    val effectiveSettings = ActorMaterializer.downcast(context.materializer).effectiveSettings(context.effectiveAttributes)
    val p = Promise[Unit]()
    (new SinkholeSubscriber[Any](p), p.future)
  }

  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Future[Unit]] = new SinkholeSink(attributes, shape)
  override def withAttributes(attr: Attributes): Module = new SinkholeSink(attr, amendShape(attr))
  override def toString: String = "SinkholeSink"
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

private[akka] final class LastOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in = Inlet[T]("lastOption.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[Option[T]] = Promise()
    (new GraphStageLogic(shape) {
      override def preStart(): Unit = pull(in)
      setHandler(in, new InHandler {
        private[this] var prev: T = null.asInstanceOf[T]

        override def onPush(): Unit = {
          prev = grab(in)
          pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          val head = prev
          prev = null.asInstanceOf[T]
          p.trySuccess(Option(head))
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          prev = null.asInstanceOf[T]
          p.tryFailure(ex)
          failStage(ex)
        }
      })
    }, p.future)
  }
}

private[akka] final class HeadOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in = Inlet[T]("headOption.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[Option[T]] = Promise()
    (new GraphStageLogic(shape) {
      override def preStart(): Unit = pull(in)
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          p.trySuccess(Option(grab(in)))
          completeStage()
        }

        override def onUpstreamFinish(): Unit = {
          p.trySuccess(None)
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          p.tryFailure(ex)
          failStage(ex)
        }
      })
    }, p.future)
  }
}

/**
 * INTERNAL API
 */
private[akka] class QueueSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], SinkQueue[T]] {
  trait RequestElementCallback[E] {
    val requestElement = new AtomicReference[AnyRef](Nil)
  }

  type Requested[E] = Promise[Option[T]]

  val in = Inlet[T]("queueSink.in")
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    type Received[E] = Try[Option[E]]

    val maxBuffer = module.attributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
    require(maxBuffer > 0, "Buffer size must be greater than 0")

    val buffer = FixedSizeBuffer[Received[T]](maxBuffer + 1)
    var currentRequest: Option[Requested[T]] = None

    val stageLogic = new GraphStageLogic(shape) with RequestElementCallback[Requested[T]] {
      override def keepGoingAfterAllPortsClosed = true

      override def preStart(): Unit = {
        val list = requestElement.getAndSet(callback.invoke _).asInstanceOf[List[Requested[T]]]
        list.reverse.foreach(callback.invoke)
        pull(in)
      }

      private val callback: AsyncCallback[Requested[T]] =
        getAsyncCallback(promise ⇒ currentRequest match {
          case Some(_) ⇒
            promise.failure(new IllegalStateException("You have to wait for previous future to be resolved to send another request"))
          case None ⇒
            if (buffer.isEmpty) currentRequest = Some(promise)
            else sendDownstream(promise)
        })

      def sendDownstream(promise: Requested[T]): Unit = {
        val e = buffer.dequeue()
        promise.complete(e)
        e match {
          case Success(_: Some[_]) ⇒ //do nothing
          case Success(None)       ⇒ completeStage()
          case Failure(t)          ⇒ failStage(t)
        }
      }

      def enqueueAndNotify(requested: Received[T]): Unit = {
        buffer.enqueue(requested)
        currentRequest match {
          case Some(p) ⇒
            sendDownstream(p)
            currentRequest = None
          case None ⇒ //do nothing
        }
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          enqueueAndNotify(Success(Some(grab(in))))
          if (buffer.used < maxBuffer - 1) pull(in)
        }
        override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
        override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))
      })
    }

    (stageLogic, new SinkQueue[T] {
      override def pull(): Future[Option[T]] = {
        val ref = stageLogic.requestElement
        val p = Promise[Option[T]]
        ref.get() match {
          case l: List[_] ⇒
            if (!ref.compareAndSet(l, p :: l))
              ref.get() match {
                case _: List[_]         ⇒ throw new IllegalStateException("Concurrent call of SinkQueue.pull() is detected")
                case f: Function1[_, _] ⇒ f.asInstanceOf[Requested[T] ⇒ Unit](p)
              }
          case f: Function1[_, _] ⇒ f.asInstanceOf[Requested[T] ⇒ Unit](p)
        }
        p.future
      }
    })
  }
}
