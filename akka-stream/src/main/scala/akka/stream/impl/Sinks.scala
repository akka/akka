/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.dispatch.ExecutionContexts
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Supervision.stoppingDecider
import akka.stream.impl.QueueSink.{ Output, Pull }
import akka.stream.impl.CancellablePublisherSink.PublisherSinkMessages
import akka.{ Done, NotUsed }

import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.AtomicModule
import akka.actor.{ ActorRef, Props }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.stage._
import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.unchecked.uncheckedVariance
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import akka.stream.scaladsl.{ Source, Sink, SinkQueueWithCancel }
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import java.util.Optional
import akka.event.Logging
import scala.language.existentials

/**
 * INTERNAL API
 */
abstract class SinkModule[-In, Mat](val shape: SinkShape[In]) extends AtomicModule {

  /**
   * Create the Subscriber or VirtualPublisher that consumes the incoming
   * stream, plus the materialized value. Since Subscriber and VirtualPublisher
   * do not share a common supertype apart from AnyRef this is what the type
   * union devolves into; unfortunately we do not have union types at our
   * disposal at this point.
   */
  def create(context: MaterializationContext): (AnyRef, Mat)

  override def replaceShape(s: Shape): AtomicModule =
    if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of a Sink, you need to wrap it in a Graph for that")
    else this

  // This is okay since we the only caller of this method is right below.
  protected def newInstance(s: SinkShape[In] @uncheckedVariance): SinkModule[In, Mat]

  override def carbonCopy: AtomicModule = newInstance(SinkShape(shape.in.carbonCopy()))

  protected def amendShape(attr: Attributes): SinkShape[In] = {
    val thisN = attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(in = Inlet(thatN + ".in"))
  }

  protected def label: String = Logging.simpleName(this)
  final override def toString: String = f"$label [${System.identityHashCode(this)}%08x]"

}

/**
 * INTERNAL API
 * Holds the downstream-most [[org.reactivestreams.Publisher]] interface of the materialized flow.
 * The stream will not have any subscribers attached at this point, which means that after prefetching
 * elements to fill the internal buffers it will assert back-pressure until
 * a subscriber connects and creates demand for elements to be emitted.
 */
private[akka] class PublisherSink[In](val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, Publisher[In]](shape) {

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
  override def withAttributes(attr: Attributes): AtomicModule = new PublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream which will just discard all received
 * elements.
 */
final class SinkholeSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, Future[Done]](shape) {

  override def create(context: MaterializationContext) = {
    val effectiveSettings = ActorMaterializerHelper.downcast(context.materializer).effectiveSettings(context.effectiveAttributes)
    val p = Promise[Done]()
    (new SinkholeSubscriber[Any](p), p.future)
  }

  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, Future[Done]] = new SinkholeSink(attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new SinkholeSink(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream.
 */
final class SubscriberSink[In](subscriber: Subscriber[In], val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = (subscriber, NotUsed)

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] = new SubscriberSink[In](subscriber, attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new SubscriberSink[In](subscriber, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * A sink that immediately cancels its upstream upon materialization.
 */
final class CancelSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, NotUsed](shape) {
  override def create(context: MaterializationContext): (Subscriber[Any], NotUsed) = (new CancellingSubscriber[Any], NotUsed)
  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, NotUsed] = new CancelSink(attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new CancelSink(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Subscriber]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorSubscriber]].
 */
final class ActorSubscriberSink[In](props: Props, val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val subscriberRef = ActorMaterializerHelper.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, ActorRef] = new ActorSubscriberSink[In](props, attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule = new ActorSubscriberSink[In](props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
final class ActorRefSink[In](ref: ActorRef, onCompleteMessage: Any,
                             val attributes: Attributes,
                             shape:          SinkShape[In]) extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = {
    val actorMaterializer = ActorMaterializerHelper.downcast(context.materializer)
    val effectiveSettings = actorMaterializer.effectiveSettings(context.effectiveAttributes)
    val subscriberRef = actorMaterializer.actorOf(
      context,
      ActorRefSinkActor.props(ref, effectiveSettings.maxInputBufferSize, onCompleteMessage))
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), NotUsed)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] =
    new ActorRefSink[In](ref, onCompleteMessage, attributes, shape)
  override def withAttributes(attr: Attributes): AtomicModule =
    new ActorRefSink[In](ref, onCompleteMessage, attr, amendShape(attr))
}

final class LastOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in: Inlet[T] = Inlet("lastOption.in")

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

final class HeadOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in: Inlet[T] = Inlet("headOption.in")

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

final class SeqStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[immutable.Seq[T]]] {
  val in = Inlet[T]("seq.in")

  override def toString: String = "SeqStage"

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

private[stream] object QueueSink {
  sealed trait Output[+T]
  final case class Pull[T](promise: Promise[Option[T]]) extends Output[T]
  case object Cancel extends Output[Nothing]
}

/**
 * INTERNAL API
 */
final class QueueSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], SinkQueueWithCancel[T]] {
  type Requested[E] = Promise[Option[E]]

  val in = Inlet[T]("queueSink.in")
  override def initialAttributes = DefaultAttributes.queueSink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "QueueSink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[Output[T]] {
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

      override def postStop(): Unit = stopCallback {
        case Pull(promise) ⇒ promise.failure(new IllegalStateException("Stream is terminated. QueueSink is detached"))
        case _             ⇒ //do nothing
      }

      private val callback: AsyncCallback[Output[T]] =
        getAsyncCallback {
          case QueueSink.Pull(pullPromise) ⇒ currentRequest match {
            case Some(_) ⇒
              pullPromise.failure(new IllegalStateException("You have to wait for previous future to be resolved to send another request"))
            case None ⇒
              if (buffer.isEmpty) currentRequest = Some(pullPromise)
              else {
                if (buffer.used == maxBuffer) tryPull(in)
                sendDownstream(pullPromise)
              }
          }
          case QueueSink.Cancel ⇒ completeStage()
        }

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

    (stageLogic, new SinkQueueWithCancel[T] {
      override def pull(): Future[Option[T]] = {
        val p = Promise[Option[T]]
        stageLogic.invoke(Pull(p))
        p.future
      }
      override def cancel(): Unit = {
        stageLogic.invoke(QueueSink.Cancel)
      }
    })
  }
}

final class SinkQueueAdapter[T](delegate: SinkQueueWithCancel[T]) extends akka.stream.javadsl.SinkQueueWithCancel[T] {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ same }
  def pull(): CompletionStage[Optional[T]] = delegate.pull().map(_.asJava)(same).toJava
  def cancel(): Unit = delegate.cancel()

}

/**
 * INTERNAL API
 *
 * Helper class to be able to express collection as a fold using mutable data
 */
private[akka] final class CollectorState[T, R](val collector: java.util.stream.Collector[T, Any, R]) {
  lazy val accumulated = collector.supplier().get()
  private lazy val accumulator = collector.accumulator()

  def update(elem: T): CollectorState[T, R] = {
    accumulator.accept(accumulated, elem)
    this
  }

  def finish(): R = collector.finisher().apply(accumulated)
}

/**
 * INTERNAL API
 *
 * Helper class to be able to express reduce as a fold for parallel collector
 */
private[akka] final class ReducerState[T, R](val collector: java.util.stream.Collector[T, Any, R]) {
  private var reduced: Any = null.asInstanceOf[Any]
  private lazy val combiner = collector.combiner()

  def update(batch: Any): ReducerState[T, R] = {
    if (reduced == null) reduced = batch
    else reduced = combiner(reduced, batch)
    this
  }

  def finish(): R = collector.finisher().apply(reduced)
}

/**
 * INTERNAL API
 */
final private[stream] class LazySink[T, M](sinkFactory: T ⇒ Future[Sink[T, M]], zeroMat: () ⇒ M) extends GraphStageWithMaterializedValue[SinkShape[T], Future[M]] {
  val in = Inlet[T]("lazySink.in")
  override def initialAttributes = DefaultAttributes.lazySink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "LazySink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(stoppingDecider)

    val promise = Promise[M]()
    val stageLogic = new GraphStageLogic(shape) with InHandler {
      override def preStart(): Unit = pull(in)

      override def onPush(): Unit = {
        try {
          val element = grab(in)
          val cb: AsyncCallback[Try[Sink[T, M]]] = getAsyncCallback {
            case Success(sink) ⇒ initInternalSource(sink, element)
            case Failure(e)    ⇒ failure(e)
          }
          sinkFactory(element).onComplete { cb.invoke }(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒ decider(e) match {
            case Supervision.Stop ⇒ failure(e)
            case _                ⇒ pull(in)
          }
        }
      }

      private def failure(ex: Throwable): Unit = {
        failStage(ex)
        promise.failure(ex)
      }

      override def onUpstreamFinish(): Unit = {
        completeStage()
        promise.tryComplete(Try(zeroMat()))
      }
      override def onUpstreamFailure(ex: Throwable): Unit = failure(ex)
      setHandler(in, this)

      private def initInternalSource(sink: Sink[T, M], firstElement: T): Unit = {
        val sourceOut = new SubSourceOutlet[T]("LazySink")
        var completed = false

        def switchToFirstElementHandlers(): Unit = {
          sourceOut.setHandler(new OutHandler {
            override def onPull(): Unit = {
              sourceOut.push(firstElement)
              if (completed) internalSourceComplete() else switchToFinalHandlers()
            }
            override def onDownstreamFinish(): Unit = internalSourceComplete()
          })

          setHandler(in, new InHandler {
            override def onPush(): Unit = sourceOut.push(grab(in))
            override def onUpstreamFinish(): Unit = {
              setKeepGoing(true)
              completed = true
            }
            override def onUpstreamFailure(ex: Throwable): Unit = internalSourceFailure(ex)
          })
        }

        def switchToFinalHandlers(): Unit = {
          sourceOut.setHandler(new OutHandler {
            override def onPull(): Unit = pull(in)
            override def onDownstreamFinish(): Unit = internalSourceComplete()
          })
          setHandler(in, new InHandler {
            override def onPush(): Unit = sourceOut.push(grab(in))
            override def onUpstreamFinish(): Unit = internalSourceComplete()
            override def onUpstreamFailure(ex: Throwable): Unit = internalSourceFailure(ex)
          })
        }

        def internalSourceComplete(): Unit = {
          sourceOut.complete()
          completeStage()
        }

        def internalSourceFailure(ex: Throwable): Unit = {
          sourceOut.fail(ex)
          failStage(ex)
        }

        switchToFirstElementHandlers()
        promise.trySuccess(Source.fromGraph(sourceOut.source).runWith(sink)(interpreter.subFusingMaterializer))
      }

    }
    (stageLogic, promise.future)
  }
}

private[stream] object CancellablePublisherSink {
  sealed trait PublisherSinkMessages[+T]
  private case class RequestMore[T](subscriber: SubscriptionWithCursor[T], demand: Long) extends PublisherSinkMessages[T]
  private case class Cancel[T](subscriber: SubscriptionWithCursor[T]) extends PublisherSinkMessages[T]
  private case class Subscribe[T](subscriber: Subscriber[_ >: T]) extends PublisherSinkMessages[T]
  private case object CancelPublisher extends PublisherSinkMessages[Nothing]

}

/**
 * INTERNAL API
 */
final private[stream] class CancellablePublisherSink[T](drainWhenNoSubscribers: Boolean) extends GraphStageWithMaterializedValue[SinkShape[T], CancellablePublisher[T]] {

  val in = Inlet[T]("CancellablePublisherSink.in")
  override def initialAttributes = DefaultAttributes.cancellablePublisherSink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "CancellablePublisherSink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val InputBuffer(initBuffer, maxBuffer) = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16))
    require(maxBuffer > 0, "Max buffer size must be greater than 0")
    require(initBuffer > 0, "Init buffer size must be greater than 0")

    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[PublisherSinkMessages[T]] with SubscriberManagement[T] with InHandler {
      private var downstreamDemand: Long = 0L
      private var upstreamCompleted = false
      private var failedWithError: Throwable = null

      override type S = SubscriptionWithCursor[T]

      override def maxBufferSize: Int = maxBuffer
      override def initialBufferSize: Int = initBuffer

      setHandler(in, this)

      override def preStart(): Unit = {
        setKeepGoing(true)
        initCallback(callback.invoke)
      }

      override def postStop(): Unit = {
        stopCallback {
          case CancellablePublisherSink.Subscribe(subscriber) ⇒
            subscriber.onSubscribe(CancelledSubscription)
            subscriber.onError(if (failedWithError == null) ActorPublisher.NormalShutdownReason else failedWithError)
          case _ ⇒ //keep silence
        }
      }

      private val callback: AsyncCallback[PublisherSinkMessages[T]] =
        getAsyncCallback {
          case CancellablePublisherSink.Subscribe(subscriber)             ⇒ registerSubscriber(subscriber.asInstanceOf[Subscriber[_ >: T]])
          case CancellablePublisherSink.RequestMore(subscriber, elements) ⇒ moreRequested(subscriber, elements)
          case CancellablePublisherSink.Cancel(subscription)              ⇒ unregisterSubscription(subscription)
          case CancellablePublisherSink.CancelPublisher                   ⇒ onUpstreamFinish()
        }

      override def onPush(): Unit = {
        if (cursors.isEmpty) {
          //drain if no subscriptions
          downstreamDemand = 0
          grab(in)
          pull(in)
        } else {
          pushToDownstream(grab(in))
          pullIfNeeded()
        }
      }

      private def pullIfNeeded(): Unit = {
        if (downstreamDemand > 0 && !hasBeenPulled(in)) {
          downstreamDemand -= 1
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        completeDownstream()
        if (cursors.isEmpty) completeStage()
        else {
          upstreamCompleted = true
          setKeepGoing(true)
        }
      }
      override def onUpstreamFailure(ex: Throwable): Unit = {
        failedWithError = ex
        abortDownstream(ex)
        failStage(ex)
      }

      override protected def shutdown(): Unit = completeStage()

      override protected def shutdownWhenNoMoreSubscriptions(): Boolean = {
        if (upstreamCompleted || !drainWhenNoSubscribers) {
          completeStage()
          true
        } else {
          //for drainWhenNoSubscribers == true when upstream not completed
          if (cursors.isEmpty) {
            downstreamDemand = 0
            if (!hasBeenPulled(in)) pull(in)
          }
          false
        }
      }

      override protected def requestFromUpstream(elements: Long): Unit = {
        downstreamDemand += elements
        pullIfNeeded()
      }

      override protected def createSubscription(_subscriber: Subscriber[_ >: T]): S = new SubscriptionWithCursor[T]() {
        override def cancel(): Unit = invoke(CancellablePublisherSink.Cancel[T](this))
        override def request(l: Long): Unit = invoke(CancellablePublisherSink.RequestMore[T](this, l))
        override def subscriber: Subscriber[_ >: T] = _subscriber
      }
    }

    (stageLogic, new CancellablePublisher[T] {
      override def subscribe(subs: Subscriber[_ >: T]): Unit = {
        ReactiveStreamsCompliance.requireNonNullSubscriber(subs)
        stageLogic.invoke(CancellablePublisherSink.Subscribe(subs))
      }
      override def cancel(): Unit = {
        stageLogic.invoke(CancellablePublisherSink.CancelPublisher)
      }
    })
  }
}

