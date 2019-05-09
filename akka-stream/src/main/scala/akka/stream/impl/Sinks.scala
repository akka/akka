/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import akka.NotUsed
import akka.actor.ActorRef
import akka.actor.Props
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.impl.QueueSink.Output
import akka.stream.impl.QueueSink.Pull
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.SinkQueueWithCancel
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ccompat._
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] abstract class SinkModule[-In, Mat](val shape: SinkShape[In])
    extends AtomicModule[SinkShape[In], Mat] {

  /**
   * Create the Subscriber or VirtualPublisher that consumes the incoming
   * stream, plus the materialized value. Since Subscriber and VirtualPublisher
   * do not share a common supertype apart from AnyRef this is what the type
   * union devolves into; unfortunately we do not have union types at our
   * disposal at this point.
   */
  def create(context: MaterializationContext): (AnyRef, Mat)

  def attributes: Attributes

  override def traversalBuilder: TraversalBuilder =
    LinearTraversalBuilder.fromModule(this, attributes).makeIsland(SinkModuleIslandTag)

  // This is okay since we the only caller of this method is right below.
  // TODO: Remove this, no longer needed
  protected def newInstance(s: SinkShape[In] @uncheckedVariance): SinkModule[In, Mat]

  protected def amendShape(attr: Attributes): SinkShape[In] = {
    val thisN = traversalBuilder.attributes.nameOrDefault(null)
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
@InternalApi private[akka] class PublisherSink[In](val attributes: Attributes, shape: SinkShape[In])
    extends SinkModule[In, Publisher[In]](shape) {

  /*
   * This method is the reason why SinkModule.create may return something that is
   * not a Subscriber: a VirtualPublisher is used in order to avoid the immediate
   * subscription a VirtualProcessor would perform (and it also saves overhead).
   */
  override def create(context: MaterializationContext): (AnyRef, Publisher[In]) = {
    val proc = new VirtualPublisher[In]
    (proc, proc)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new PublisherSink[In](attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, Publisher[In]] =
    new PublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class FanoutPublisherSink[In](val attributes: Attributes, shape: SinkShape[In])
    extends SinkModule[In, Publisher[In]](shape) {

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val actorMaterializer = ActorMaterializerHelper.downcast(context.materializer)
    val impl = actorMaterializer.actorOf(
      context,
      FanoutProcessorImpl.props(context.effectiveAttributes, actorMaterializer.settings))
    val fanoutProcessor = new ActorProcessor[In, In](impl)
    impl ! ExposedPublisher(fanoutProcessor.asInstanceOf[ActorPublisher[Any]])
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    (fanoutProcessor, fanoutProcessor)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](attributes, shape)

  override def withAttributes(attr: Attributes): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream.
 */
@InternalApi private[akka] final class SubscriberSink[In](
    subscriber: Subscriber[In],
    val attributes: Attributes,
    shape: SinkShape[In])
    extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = (subscriber, NotUsed)

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] =
    new SubscriberSink[In](subscriber, attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, NotUsed] =
    new SubscriberSink[In](subscriber, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * A sink that immediately cancels its upstream upon materialization.
 */
@InternalApi private[akka] final class CancelSink(val attributes: Attributes, shape: SinkShape[Any])
    extends SinkModule[Any, NotUsed](shape) {
  override def create(context: MaterializationContext): (Subscriber[Any], NotUsed) =
    (new CancellingSubscriber[Any], NotUsed)
  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, NotUsed] =
    new CancelSink(attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[Any, NotUsed] = new CancelSink(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Subscriber]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorSubscriber]].
 */
@InternalApi private[akka] final class ActorSubscriberSink[In](
    props: Props,
    val attributes: Attributes,
    shape: SinkShape[In])
    extends SinkModule[In, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val subscriberRef = ActorMaterializerHelper.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, ActorRef] =
    new ActorSubscriberSink[In](props, attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, ActorRef] =
    new ActorSubscriberSink[In](props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorRefSink[In](
    ref: ActorRef,
    onCompleteMessage: Any,
    onFailureMessage: Throwable => Any,
    val attributes: Attributes,
    shape: SinkShape[In])
    extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = {
    val actorMaterializer = ActorMaterializerHelper.downcast(context.materializer)
    val maxInputBufferSize = context.effectiveAttributes.mandatoryAttribute[Attributes.InputBuffer].max
    val subscriberRef = actorMaterializer.actorOf(
      context,
      ActorRefSinkActor.props(ref, maxInputBufferSize, onCompleteMessage, onFailureMessage))
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), NotUsed)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] =
    new ActorRefSink[In](ref, onCompleteMessage, onFailureMessage, attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, NotUsed] =
    new ActorRefSink[In](ref, onCompleteMessage, onFailureMessage, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class TakeLastStage[T](n: Int)
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[immutable.Seq[T]]] {
  if (n <= 0)
    throw new IllegalArgumentException("requirement failed: n must be greater than 0")

  val in: Inlet[T] = Inlet("takeLast.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[immutable.Seq[T]] = Promise()
    (new GraphStageLogic(shape) with InHandler {
      private[this] val buffer = mutable.Queue.empty[T]
      private[this] var count = 0

      override def preStart(): Unit = pull(in)

      override def onPush(): Unit = {
        buffer.enqueue(grab(in))
        if (count < n)
          count += 1
        else
          buffer.dequeue()
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        val elements = buffer.toList
        buffer.clear()
        p.trySuccess(elements)
        completeStage()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        p.tryFailure(ex)
        failStage(ex)
      }

      setHandler(in, this)
    }, p.future)
  }

  override def toString: String = "TakeLastStage"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class HeadOptionStage[T]
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in: Inlet[T] = Inlet("headOption.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[Option[T]] = Promise()
    (new GraphStageLogic(shape) with InHandler {
      override def preStart(): Unit = pull(in)

      def onPush(): Unit = {
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

      override def postStop(): Unit = {
        if (!p.isCompleted) p.failure(new AbruptStageTerminationException(this))
      }

      setHandler(in, this)
    }, p.future)
  }

  override def toString: String = "HeadOptionStage"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class SeqStage[T, That](implicit cbf: Factory[T, That with immutable.Iterable[_]])
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[That]] {
  val in = Inlet[T]("seq.in")

  override def toString: String = "SeqStage"

  override val shape: SinkShape[T] = SinkShape.of(in)

  override protected def initialAttributes: Attributes = DefaultAttributes.seqSink

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[That] = Promise()
    val logic = new GraphStageLogic(shape) with InHandler {
      val buf = cbf.newBuilder

      override def preStart(): Unit = pull(in)

      def onPush(): Unit = {
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

      override def postStop(): Unit = {
        if (!p.isCompleted) p.failure(new AbruptStageTerminationException(this))
      }

      setHandler(in, this)
    }

    (logic, p.future)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object QueueSink {
  sealed trait Output[+T]
  final case class Pull[T](promise: Promise[Option[T]]) extends Output[T]
  case object Cancel extends Output[Nothing]
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class QueueSink[T]()
    extends GraphStageWithMaterializedValue[SinkShape[T], SinkQueueWithCancel[T]] {
  type Requested[E] = Promise[Option[E]]

  val in = Inlet[T]("queueSink.in")
  override def initialAttributes = DefaultAttributes.queueSink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "QueueSink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with InHandler with SinkQueueWithCancel[T] {
      type Received[E] = Try[Option[E]]

      val maxBuffer = inheritedAttributes.get[InputBuffer](InputBuffer(16, 16)).max
      require(maxBuffer > 0, "Buffer size must be greater than 0")

      var buffer: Buffer[Received[T]] = _
      var currentRequest: Option[Requested[T]] = None

      override def preStart(): Unit = {
        // Allocates one additional element to hold stream
        // closed/failure indicators
        buffer = Buffer(maxBuffer + 1, materializer)
        setKeepGoing(true)
        pull(in)
      }

      private val callback = getAsyncCallback[Output[T]] {
        case QueueSink.Pull(pullPromise) =>
          currentRequest match {
            case Some(_) =>
              pullPromise.failure(
                new IllegalStateException(
                  "You have to wait for previous future to be resolved to send another request"))
            case None =>
              if (buffer.isEmpty) currentRequest = Some(pullPromise)
              else {
                if (buffer.used == maxBuffer) tryPull(in)
                sendDownstream(pullPromise)
              }
          }
        case QueueSink.Cancel => completeStage()
      }

      def sendDownstream(promise: Requested[T]): Unit = {
        val e = buffer.dequeue()
        promise.complete(e)
        e match {
          case Success(_: Some[_]) => //do nothing
          case Success(None)       => completeStage()
          case Failure(t)          => failStage(t)
        }
      }

      def enqueueAndNotify(requested: Received[T]): Unit = {
        buffer.enqueue(requested)
        currentRequest match {
          case Some(p) =>
            sendDownstream(p)
            currentRequest = None
          case None => //do nothing
        }
      }

      def onPush(): Unit = {
        enqueueAndNotify(Success(Some(grab(in))))
        if (buffer.used < maxBuffer) pull(in)
      }

      override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
      override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))

      setHandler(in, this)

      // SinkQueueWithCancel impl
      override def pull(): Future[Option[T]] = {
        val p = Promise[Option[T]]
        callback
          .invokeWithFeedback(Pull(p))
          .failed
          .foreach {
            case NonFatal(e) => p.tryFailure(e)
            case _           => ()
          }(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        p.future
      }
      override def cancel(): Unit = {
        callback.invoke(QueueSink.Cancel)
      }
    }

    (stageLogic, stageLogic)
  }
}

/**
 * INTERNAL API
 *
 * Helper class to be able to express collection as a fold using mutable data
 */
@InternalApi private[akka] final class CollectorState[T, R](val collector: java.util.stream.Collector[T, Any, R]) {
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
@InternalApi private[akka] final class ReducerState[T, R](val collector: java.util.stream.Collector[T, Any, R]) {
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
@InternalApi final private[stream] class LazySink[T, M](sinkFactory: T => Future[Sink[T, M]])
    extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[M]]] {
  val in = Inlet[T]("lazySink.in")
  override def initialAttributes = DefaultAttributes.lazySink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "LazySink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {

    val promise = Promise[Option[M]]()
    val stageLogic = new GraphStageLogic(shape) with InHandler {
      var switching = false
      override def preStart(): Unit = pull(in)

      override def onPush(): Unit = {
        val element = grab(in)
        switching = true
        val cb: AsyncCallback[Try[Sink[T, M]]] =
          getAsyncCallback {
            case Success(sink) =>
              // check if the stage is still in need for the lazy sink
              // (there could have been an onUpstreamFailure in the meantime that has completed the promise)
              if (!promise.isCompleted) {
                try {
                  val mat = switchTo(sink, element)
                  promise.success(Some(mat))
                  setKeepGoing(true)
                } catch {
                  case NonFatal(e) =>
                    promise.failure(e)
                    failStage(e)
                }
              }
            case Failure(e) =>
              promise.failure(e)
              failStage(e)
          }
        try {
          sinkFactory(element).onComplete(cb.invoke)(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) =>
            promise.failure(e)
            failStage(e)
        }
      }

      override def onUpstreamFinish(): Unit = {
        // ignore onUpstreamFinish while the stage is switching but setKeepGoing
        //
        if (switching) {
          // there is a cached element -> the stage must not be shut down automatically because isClosed(in) is satisfied
          setKeepGoing(true)
        } else {
          promise.success(None)
          super.onUpstreamFinish()
        }
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        promise.failure(ex)
        super.onUpstreamFailure(ex)
      }

      setHandler(in, this)

      private def switchTo(sink: Sink[T, M], firstElement: T): M = {

        var firstElementPushed = false

        val subOutlet = new SubSourceOutlet[T]("LazySink")

        val matVal = Source.fromGraph(subOutlet.source).runWith(sink)(interpreter.subFusingMaterializer)

        def maybeCompleteStage(): Unit = {
          if (isClosed(in) && subOutlet.isClosed) {
            completeStage()
          }
        }

        // The stage must not be shut down automatically; it is completed when maybeCompleteStage decides
        setKeepGoing(true)

        setHandler(
          in,
          new InHandler {
            override def onPush(): Unit = {
              subOutlet.push(grab(in))
            }
            override def onUpstreamFinish(): Unit = {
              if (firstElementPushed) {
                subOutlet.complete()
                maybeCompleteStage()
              }
            }
            override def onUpstreamFailure(ex: Throwable): Unit = {
              // propagate exception irrespective if the cached element has been pushed or not
              subOutlet.fail(ex)
              // #25410 if we fail the stage here directly, the SubSource may not have been started yet,
              // which can happen if upstream fails immediately after emitting a first value.
              // The SubSource won't be started until the stream shuts down, which means downstream won't see the failure,
              // scheduling it lets the interpreter first start the substream
              getAsyncCallback[Throwable](failStage).invoke(ex)
            }
          })

        subOutlet.setHandler(new OutHandler {
          override def onPull(): Unit = {
            if (firstElementPushed) {
              pull(in)
            } else {
              // the demand can be satisfied right away by the cached element
              firstElementPushed = true
              subOutlet.push(firstElement)
              // in.onUpstreamFinished was not propagated if it arrived before the cached element was pushed
              // -> check if the completion must be propagated now
              if (isClosed(in)) {
                subOutlet.complete()
                maybeCompleteStage()
              }
            }
          }
          override def onDownstreamFinish(): Unit = {
            if (!isClosed(in)) {
              cancel(in)
            }
            maybeCompleteStage()
          }
        })

        matVal
      }

    }
    (stageLogic, promise.future)
  }
}
