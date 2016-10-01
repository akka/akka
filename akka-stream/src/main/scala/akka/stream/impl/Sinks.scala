/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.dispatch.ExecutionContexts
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Supervision.stoppingDecider
import akka.stream.impl.QueueSink.{ Output, Pull }
import akka.{ Done, NotUsed }
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.AtomicModule
import akka.actor.{ ActorRef, Props }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.stage._
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import akka.stream.scaladsl.{ Sink, SinkQueueWithCancel, Source }
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import java.util.Optional

import akka.event.Logging

import scala.collection.mutable.ArrayBuffer

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
 */
private[akka] final class FanoutPublisherSink[In](
  val attributes: Attributes,
  shape:          SinkShape[In])
  extends SinkModule[In, Publisher[In]](shape) {

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val actorMaterializer = ActorMaterializerHelper.downcast(context.materializer)
    val impl = actorMaterializer.actorOf(
      context,
      FanoutProcessorImpl.props(actorMaterializer.effectiveSettings(attributes)))
    val fanoutProcessor = new ActorProcessor[In, In](impl)
    impl ! ExposedPublisher(fanoutProcessor.asInstanceOf[ActorPublisher[Any]])
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    (fanoutProcessor, fanoutProcessor)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](attributes, shape)

  override def withAttributes(attr: Attributes): AtomicModule =
    new FanoutPublisherSink[In](attr, amendShape(attr))
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
    (new GraphStageLogic(shape) with InHandler {
      private[this] var prev: T = null.asInstanceOf[T]

      override def preStart(): Unit = pull(in)

      def onPush(): Unit = {
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

      setHandler(in, this)
    }, p.future)
  }

  override def toString: String = "LastOptionStage"
}

final class HeadOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

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

      setHandler(in, this)
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
    val logic = new GraphStageLogic(shape) with InHandler {
      val buf = Vector.newBuilder[T]

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

      setHandler(in, this)
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
    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[Output[T]] with InHandler {
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

      def onPush(): Unit = {
        enqueueAndNotify(Success(Some(grab(in))))
        if (buffer.used < maxBuffer) pull(in)
      }

      override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
      override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))

      setHandler(in, this)
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

/**
 * INTERNAL API
 */
final class FoldResourceSink[T, S](
  open:      () ⇒ S,
  writeData: (S, T) ⇒ Unit,
  close:     (S) ⇒ Unit) extends GraphStageWithMaterializedValue[SinkShape[T], Future[Done]] {
  val in = Inlet[T]("FoldResourceSink.in")
  override val shape = SinkShape(in)
  override def initialAttributes: Attributes = DefaultAttributes.foldResourceSink

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val completion = Promise[Done]()

    val logic: GraphStageLogic = new GraphStageLogic(shape) with InHandler {
      lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
      var resource: S = _
      setHandler(in, this)

      override def preStart(): Unit = {
        try {
          resource = open()
        } catch {
          case NonFatal(e) ⇒
            completion.failure(e)
            throw e
        }
        pull(in)
      }

      final override def onPush(): Unit = {
        try {
          writeData(resource, grab(in))
          pull(in)
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop ⇒
              close(resource)
              doFailStage(ex)
            case Supervision.Restart ⇒
              restartState()
              pull(in)
            case Supervision.Resume ⇒
              pull(in)
          }
        }
      }

      override def onUpstreamFinish(): Unit = closeStage()

      override def onUpstreamFailure(ex: Throwable): Unit = {
        close(resource)
        doFailStage(ex)
      }

      private def restartState(): Unit = {
        close(resource)
        resource = open()
      }

      private def closeStage(): Unit =
        try {
          close(resource)
          doCompleteStage()
        } catch {
          case NonFatal(ex) ⇒ doFailStage(ex)
        }

      private def doFailStage(th: Throwable): Unit = {
        completion.failure(th)
        failStage(th)
      }

      private def doCompleteStage(): Unit = {
        completion.success(Done)
        completeStage()
      }
    }

    (logic, completion.future)
  }

  override def toString = "FoldResourceSink"
}

/**
 * INTERNAL API
 */
final class FoldResourceSinkAsync[T, S](
  open:      () ⇒ Future[S],
  writeData: (S, T) ⇒ Future[Unit],
  close:     (S) ⇒ Future[Unit])
  extends GraphStageWithMaterializedValue[SinkShape[T], Future[Done]] {
  val in = Inlet[T]("FoldResourceSinkAsync.out")
  override val shape = SinkShape(in)
  override def initialAttributes: Attributes = DefaultAttributes.foldResourceSinkAsync

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val completion = Promise[Done]()
    val logic: GraphStageLogic = new GraphStageLogic(shape) with InHandler {
      implicit val context = ExecutionContexts.sameThreadExecutionContext
      val handleWriteResultCallback = getAsyncCallback[Try[Unit]](handleWriteResult).invoke _

      /**
       * Exceptions occurred in this stage.
       * This stage will fail if any exception exists. This is checked after every close().
       * The second one and after will be added as suppressed exceptions of the first one.
       */
      val exceptions: ArrayBuffer[Throwable] = ArrayBuffer()

      lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)

      var resource: Option[S] = None

      /** Flag to avoid double close() the resource. */
      var isCloseRequested = false

      /** Flag to indicate this stage is finishing. This flag is checked after each close(). */
      var isFinishing = false

      var writeFuture: Future[Unit] = Future.successful(())
      var closeFuture: Future[Unit] = Future.successful(())

      val errorHandler: PartialFunction[Throwable, Unit] = {
        case NonFatal(ex) ⇒ decider(ex) match {
          case Supervision.Stop ⇒
            exceptions += ex
            isFinishing = true
            closeThen(() ⇒ {})
          case Supervision.Restart ⇒ restartState()
          case Supervision.Resume ⇒
            if (!isClosed(in)) {
              pull(in)
            }
        }
      }

      setHandler(in, this)

      override def preStart(): Unit = {
        openResource()
        setKeepGoing(true)
      }

      private def openResource(): Unit = {
        val cb = getAsyncCallback[Try[S]] {
          case scala.util.Success(res) ⇒
            resource = Some(res)
            isCloseRequested = false

            if (isClosed(in)) {
              closeThen(() ⇒ {})
            } else {
              pull(in)
            }
          case scala.util.Failure(t) ⇒
            exceptions += t
            finishStage()
        }
        try {
          open().onComplete(cb.invoke)
        } catch {
          case NonFatal(t) ⇒
            exceptions += t
            finishStage()
        }
      }

      private def onWriteComplete(f: Try[Unit] ⇒ Unit): Unit = {
        val cb = getAsyncCallback[Try[Unit]](f)
        writeFuture.onComplete(cb.invoke)
      }

      def handleWriteResult(t: Try[Unit]): Unit = t match {
        case scala.util.Success(_) ⇒
          if (!isClosed(in)) {
            pull(in)
          }
        case scala.util.Failure(ex) ⇒ errorHandler(ex)
      }

      final override def onPush(): Unit = {
        val elem = grab(in)
        try {
          resource match {
            case Some(r) ⇒
              writeFuture = writeData(r, elem)
              // Optimization
              writeFuture.value match {
                case None    ⇒ writeFuture.onComplete(handleWriteResultCallback)
                case Some(v) ⇒ handleWriteResult(v)
              }
            case None ⇒
              throw new IllegalStateException("An element pushed while the resource is None")
          }
        } catch errorHandler
      }

      override def onUpstreamFinish(): Unit = {
        isFinishing = true
        resource.foreach(_ ⇒ closeThen(() ⇒ {}))
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        exceptions += ex
        isFinishing = true
        resource.foreach(_ ⇒ closeThen(() ⇒ {}))
      }

      // f will be called on this stage's thread
      private def closeThen(f: () ⇒ Unit): Unit = {
        resource match {
          case Some(r) ⇒

            val closeCallback = getAsyncCallback[Try[Unit]] { tu ⇒
              tu.failed.foreach(exceptions += _)
              if (exceptions.nonEmpty || isFinishing) {
                finishStage()
              } else {
                f()
              }
            }

            if (!isCloseRequested) {
              isCloseRequested = true
              onWriteComplete(_ ⇒
                try {
                  closeFuture = close(r)
                  closeFuture.onComplete(closeCallback.invoke)
                } catch {
                  case NonFatal(ex) ⇒
                    exceptions += ex
                    finishStage()
                }
              )
            } else {
              closeFuture.onComplete(closeCallback.invoke)
            }
          case None ⇒ f()
        }
      }

      private def restartState(): Unit = closeThen { () ⇒
        resource = None
        isCloseRequested = false
        openResource()
      }

      private def finishStage(): Unit = {
        exceptions.headOption match {
          case None ⇒
            completion.success(Done)
            completeStage()
          case Some(ex) ⇒
            exceptions.tail.foreach { th ⇒ ex.addSuppressed(th) }
            completion.failure(ex)
            failStage(ex)
        }
      }
    }

    (logic, completion.future)
  }
  override def toString = "FoldResourceSinkAsync"

}
