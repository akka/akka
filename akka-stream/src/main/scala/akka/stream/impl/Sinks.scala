/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.{ Done, NotUsed }
import akka.actor.{ ActorRef, Props }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.Module
import akka.stream.stage._
import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import akka.stream.scaladsl.SinkQueue
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import java.util.Optional

/**
 * INTERNAL API
 */
private[akka] abstract class SinkModule[-In, Mat](val shape: SinkShape[In]) extends Module {

  /**
   * Create the Subscriber or VirtualPublisher that consumes the incoming
   * stream, plus the materialized value. Since Subscriber and VirtualPublisher
   * do not share a common supertype apart from AnyRef this is what the type
   * union devolves into; unfortunately we do not have union types at our
   * disposal at this point.
   */
  def create(context: MaterializationContext): (AnyRef, Mat)

  override def replaceShape(s: Shape): Module =
    if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of a Sink, you need to wrap it in a Graph for that")
    else this

  // This is okay since we the only caller of this method is right below.
  protected def newInstance(s: SinkShape[In] @uncheckedVariance): SinkModule[In, Mat]

  override def carbonCopy: Module = newInstance(SinkShape(shape.in.carbonCopy()))

  override def subModules: Set[Module] = Set.empty

  protected def amendShape(attr: Attributes): SinkShape[In] = {
    val thisN = attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(in = Inlet(thatN + ".in"))
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

  /*
   * This method is the reason why SinkModule.create may return something that is
   * not a Subscriber: a VirtualPublisher is used in order to avoid the immediate
   * subscription a VirtualProcessor would perform (and it also saves overhead).
   */
  override def create(context: MaterializationContext): (AnyRef, Publisher[In]) = {
    val proc = new VirtualPublisher[In]
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
private[akka] final class SinkholeSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, Future[Done]](shape) {

  override def create(context: MaterializationContext) = {
    val effectiveSettings = ActorMaterializer.downcast(context.materializer).effectiveSettings(context.effectiveAttributes)
    val p = Promise[Done]()
    (new SinkholeSubscriber[Any](p), p.future)
  }

  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Future[Done]] = new SinkholeSink(attributes, shape)
  override def withAttributes(attr: Attributes): Module = new SinkholeSink(attr, amendShape(attr))
  override def toString: String = "SinkholeSink"
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream.
 */
private[akka] final class SubscriberSink[In](subscriber: Subscriber[In], val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = (subscriber, NotUsed)

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] = new SubscriberSink[In](subscriber, attributes, shape)
  override def withAttributes(attr: Attributes): Module = new SubscriberSink[In](subscriber, attr, amendShape(attr))
  override def toString: String = "SubscriberSink"
}

/**
 * INTERNAL API
 * A sink that immediately cancels its upstream upon materialization.
 */
private[akka] final class CancelSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, NotUsed](shape) {
  override def create(context: MaterializationContext): (Subscriber[Any], NotUsed) = (new CancellingSubscriber[Any], NotUsed)
  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, NotUsed] = new CancelSink(attributes, shape)
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
                                           shape: SinkShape[In]) extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = {
    val actorMaterializer = ActorMaterializer.downcast(context.materializer)
    val effectiveSettings = actorMaterializer.effectiveSettings(context.effectiveAttributes)
    val subscriberRef = actorMaterializer.actorOf(context,
      ActorRefSinkActor.props(ref, effectiveSettings.maxInputBufferSize, onCompleteMessage))
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), NotUsed)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] =
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

  override def toString: String = "LastOptionStage"
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

  override def toString: String = "HeadOptionStage"
}

private[akka] final class SeqStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[immutable.Seq[T]]] {
  val in = Inlet[T]("seq.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override protected def initialAttributes: Attributes = DefaultAttributes.seqSink

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[immutable.Seq[T]] = Promise()
    val logic = new GraphStageLogic(shape) {
      val buf = Vector.newBuilder[T]

      override def preStart(): Unit = pull(in)

      setHandler(in, new InHandler {

        override def onPush(): Unit = {
          buf += grab(in)
          pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          val result = buf.result()
          p.trySuccess(result)
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          p.tryFailure(ex)
          failStage(ex)
        }
      })
    }

    (logic, p.future)
  }
}

/**
 * INTERNAL API
 */
final private[stream] class QueueSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], SinkQueue[T]] {
  type Requested[E] = Promise[Option[E]]

  val in = Inlet[T]("queueSink.in")
  override def initialAttributes = DefaultAttributes.queueSink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[Requested[T]] {
      type Received[E] = Try[Option[E]]

      val maxBuffer = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
      require(maxBuffer > 0, "Buffer size must be greater than 0")

      var buffer: Buffer[Received[T]] = _
      var currentRequest: Option[Requested[T]] = None

      override def preStart(): Unit = {
        // Allocates one additional element to hold stream
        // closed/failure indicators
        buffer = Buffer(maxBuffer + 1, materializer)
        setKeepGoing(true)
        initCallback(callback.invoke)
        pull(in)
      }

      override def postStop(): Unit = stopCallback(promise ⇒
        promise.failure(new IllegalStateException("Stream is terminated. QueueSink is detached")))

      private val callback: AsyncCallback[Requested[T]] =
        getAsyncCallback(promise ⇒ currentRequest match {
          case Some(_) ⇒
            promise.failure(new IllegalStateException("You have to wait for previous future to be resolved to send another request"))
          case None ⇒
            if (buffer.isEmpty) currentRequest = Some(promise)
            else {
              if (buffer.used == maxBuffer) tryPull(in)
              sendDownstream(promise)
            }
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
          if (buffer.used < maxBuffer) pull(in)
        }
        override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
        override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))
      })
    }

    (stageLogic, new SinkQueue[T] {
      override def pull(): Future[Option[T]] = {
        val p = Promise[Option[T]]
        stageLogic.invoke(p)
        p.future
      }
    })
  }
}

private[akka] final class SinkQueueAdapter[T](delegate: SinkQueue[T]) extends akka.stream.javadsl.SinkQueue[T] {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ same }
  def pull(): CompletionStage[Optional[T]] = delegate.pull().map(_.asJava)(same).toJava
}
