/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.event.Logging
import akka.stream._
import akka.stream.impl.ReactiveStreamsCompliance._
import akka.stream.impl.StreamLayout.{ CopiedModule, Module }
import akka.stream.impl.fusing.GraphInterpreter.{ DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic, GraphAssembly }
import akka.stream.impl.{ ActorPublisher, ReactiveStreamsCompliance }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import org.reactivestreams.{ Subscriber, Subscription }

import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[stream] case class GraphModule(assembly: GraphAssembly, shape: Shape, attributes: Attributes) extends Module {
  override def subModules: Set[Module] = Set.empty
  override def withAttributes(newAttr: Attributes): Module = copy(attributes = newAttr)

  override final def carbonCopy: Module = {
    val newShape = shape.deepCopy()
    replaceShape(newShape)
  }

  override final def replaceShape(newShape: Shape): Module =
    CopiedModule(newShape, attributes, copyOf = this)
}

/**
 * INTERNAL API
 */
private[stream] object ActorGraphInterpreter {
  trait BoundaryEvent extends DeadLetterSuppression with NoSerializationVerificationNeeded

  final case class OnError(id: Int, cause: Throwable) extends BoundaryEvent
  final case class OnComplete(id: Int) extends BoundaryEvent
  final case class OnNext(id: Int, e: Any) extends BoundaryEvent
  final case class OnSubscribe(id: Int, subscription: Subscription) extends BoundaryEvent

  final case class RequestMore(id: Int, demand: Long) extends BoundaryEvent
  final case class Cancel(id: Int) extends BoundaryEvent
  final case class SubscribePending(id: Int) extends BoundaryEvent
  final case class ExposedPublisher(id: Int, publisher: ActorPublisher[Any]) extends BoundaryEvent

  final case class AsyncInput(logic: GraphStageLogic, evt: Any, handler: (Any) ⇒ Unit) extends BoundaryEvent

  case object Resume extends BoundaryEvent

  final class BoundarySubscription(val parent: ActorRef, val id: Int) extends Subscription {
    override def request(elements: Long): Unit = parent ! RequestMore(id, elements)
    override def cancel(): Unit = parent ! Cancel(id)
    override def toString = s"BoundarySubscription[$parent, $id]"
  }

  final class BoundarySubscriber(val parent: ActorRef, id: Int) extends Subscriber[Any] {
    override def onError(cause: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(cause)
      parent ! OnError(id, cause)
    }
    override def onComplete(): Unit = parent ! OnComplete(id)
    override def onNext(element: Any): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(element)
      parent ! OnNext(id, element)
    }
    override def onSubscribe(subscription: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
      parent ! OnSubscribe(id, subscription)
    }
  }

  def props(assembly: GraphAssembly,
            inHandlers: Array[InHandler],
            outHandlers: Array[OutHandler],
            logics: Array[GraphStageLogic],
            shape: Shape,
            settings: ActorMaterializerSettings,
            mat: Materializer): Props =
    Props(new ActorGraphInterpreter(assembly, inHandlers, outHandlers, logics, shape, settings, mat)).withDeploy(Deploy.local)

  class BatchingActorInputBoundary(size: Int, id: Int) extends UpstreamBoundaryStageLogic[Any] {
    require(size > 0, "buffer size cannot be zero")
    require((size & (size - 1)) == 0, "buffer size must be a power of two")

    private var upstream: Subscription = _
    private val inputBuffer = Array.ofDim[AnyRef](size)
    private var inputBufferElements = 0
    private var nextInputElementCursor = 0
    private var upstreamCompleted = false
    private var downstreamCanceled = false
    private val IndexMask = size - 1

    private def requestBatchSize = math.max(1, inputBuffer.length / 2)
    private var batchRemaining = requestBatchSize

    val out: Outlet[Any] = Outlet[Any]("UpstreamBoundary" + id)
    out.id = 0

    private def dequeue(): Any = {
      val elem = inputBuffer(nextInputElementCursor)
      require(elem ne null, "Internal queue must never contain a null")
      inputBuffer(nextInputElementCursor) = null

      batchRemaining -= 1
      if (batchRemaining == 0 && !upstreamCompleted) {
        tryRequest(upstream, requestBatchSize)
        batchRemaining = requestBatchSize
      }

      inputBufferElements -= 1
      nextInputElementCursor = (nextInputElementCursor + 1) & IndexMask
      elem
    }
    private def clear(): Unit = {
      java.util.Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
      inputBufferElements = 0
    }

    def cancel(): Unit = {
      downstreamCanceled = true
      if (!upstreamCompleted) {
        upstreamCompleted = true
        if (upstream ne null) tryCancel(upstream)
        clear()
      }
    }

    def onNext(elem: Any): Unit = {
      if (!upstreamCompleted) {
        if (inputBufferElements == size) throw new IllegalStateException("Input buffer overrun")
        inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
        inputBufferElements += 1
        if (isAvailable(out)) push(out, dequeue())
      }
    }

    def onError(e: Throwable): Unit =
      if (!upstreamCompleted || !downstreamCanceled) {
        upstreamCompleted = true
        clear()
        fail(out, e)
      }

    // Call this when an error happens that does not come from the usual onError channel
    // (exceptions while calling RS interfaces, abrupt termination etc)
    def onInternalError(e: Throwable): Unit = {
      if (!(upstreamCompleted || downstreamCanceled) && (upstream ne null)) {
        upstream.cancel()
      }
      onError(e)
    }

    def onComplete(): Unit =
      if (!upstreamCompleted) {
        upstreamCompleted = true
        if (inputBufferElements == 0) complete(out)
      }

    def onSubscribe(subscription: Subscription): Unit = {
      require(subscription != null, "Subscription cannot be null")
      if (upstreamCompleted)
        tryCancel(subscription)
      else if (downstreamCanceled) {
        upstreamCompleted = true
        tryCancel(subscription)
      } else {
        upstream = subscription
        // Prefetch
        tryRequest(upstream, inputBuffer.length)
      }
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (inputBufferElements > 1) push(out, dequeue())
        else if (inputBufferElements == 1) {
          if (upstreamCompleted) {
            push(out, dequeue())
            complete(out)
          } else push(out, dequeue())
        } else if (upstreamCompleted) {
          complete(out)
        }
      }

      override def onDownstreamFinish(): Unit = cancel()
    })

  }

  private[stream] class ActorOutputBoundary(actor: ActorRef, id: Int) extends DownstreamBoundaryStageLogic[Any] {
    val in: Inlet[Any] = Inlet[Any]("UpstreamBoundary" + id)
    in.id = 0

    private var exposedPublisher: ActorPublisher[Any] = _

    private var subscriber: Subscriber[Any] = _
    private var downstreamDemand: Long = 0L
    // This flag is only used if complete/fail is called externally since this op turns into a Finished one inside the
    // interpreter (i.e. inside this op this flag has no effects since if it is completed the op will not be invoked)
    private var downstreamCompleted = false
    // when upstream failed before we got the exposed publisher
    private var upstreamFailed: Option[Throwable] = None

    private def onNext(elem: Any): Unit = {
      downstreamDemand -= 1
      tryOnNext(subscriber, elem)
    }

    private def complete(): Unit = {
      if (!downstreamCompleted) {
        downstreamCompleted = true
        if (exposedPublisher ne null) exposedPublisher.shutdown(None)
        if (subscriber ne null) tryOnComplete(subscriber)
      }
    }

    def fail(e: Throwable): Unit = {
      if (!downstreamCompleted) {
        downstreamCompleted = true
        if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
        if ((subscriber ne null) && !e.isInstanceOf[SpecViolation]) tryOnError(subscriber, e)
      } else if (exposedPublisher == null && upstreamFailed.isEmpty) {
        // fail called before the exposed publisher arrived, we must store it and fail when we're first able to
        upstreamFailed = Some(e)
      }
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        onNext(grab(in))
        if (downstreamCompleted) cancel(in)
        else if (downstreamDemand > 0) pull(in)
      }

      override def onUpstreamFinish(): Unit = complete()

      override def onUpstreamFailure(cause: Throwable): Unit = fail(cause)
    })

    def subscribePending(): Unit =
      exposedPublisher.takePendingSubscribers() foreach { sub ⇒
        if (subscriber eq null) {
          subscriber = sub
          tryOnSubscribe(subscriber, new BoundarySubscription(actor, id))
        } else
          rejectAdditionalSubscriber(subscriber, s"${Logging.simpleName(this)}")
      }

    def exposedPublisher(publisher: ActorPublisher[Any]): Unit = {
      upstreamFailed match {
        case _: Some[_] ⇒
          publisher.shutdown(upstreamFailed)
        case _ ⇒
          exposedPublisher = publisher
      }
    }

    def requestMore(elements: Long): Unit = {
      if (elements < 1) {
        cancel(in)
        fail(ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
      } else {
        downstreamDemand += elements
        if (downstreamDemand < 0)
          downstreamDemand = Long.MaxValue // Long overflow, Reactive Streams Spec 3:17: effectively unbounded
        if (!hasBeenPulled(in) && !isClosed(in)) pull(in)
      }
    }

    def cancel(): Unit = {
      downstreamCompleted = true
      subscriber = null
      exposedPublisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      cancel(in)
    }

  }

}

/**
 * INTERNAL API
 */
private[stream] class ActorGraphInterpreter(
  assembly: GraphAssembly,
  inHandlers: Array[InHandler],
  outHandlers: Array[OutHandler],
  logics: Array[GraphStageLogic],
  shape: Shape,
  settings: ActorMaterializerSettings,
  mat: Materializer) extends Actor {
  import ActorGraphInterpreter._

  val interpreter = new GraphInterpreter(
    assembly,
    mat,
    Logging(this),
    inHandlers,
    outHandlers,
    logics,
    (logic, event, handler) ⇒ self ! AsyncInput(logic, event, handler),
    settings.fuzzingMode)

  private val inputs = Array.tabulate(shape.inlets.size)(new BatchingActorInputBoundary(settings.maxInputBufferSize, _))
  private val outputs = Array.tabulate(shape.outlets.size)(new ActorOutputBoundary(self, _))

  private var subscribesPending = inputs.length

  // Limits the number of events processed by the interpreter before scheduling a self-message for fairness with other
  // actors.
  // TODO: Better heuristic here (take into account buffer size, connection count, 4 events per element, have a max)
  val eventLimit = settings.maxInputBufferSize * (inputs.length + outputs.length) * 2
  // Limits the number of events processed by the interpreter on an abort event.
  // TODO: Better heuristic here
  private val abortLimit = eventLimit * 2
  private var resumeScheduled = false

  override def preStart(): Unit = {
    var i = 0
    while (i < inputs.length) {
      interpreter.attachUpstreamBoundary(i, inputs(i))
      i += 1
    }
    val offset = assembly.connectionCount - outputs.length
    i = 0
    while (i < outputs.length) {
      interpreter.attachDownstreamBoundary(i + offset, outputs(i))
      i += 1
    }
    interpreter.init()
  }

  override def receive: Receive = {
    // Cases that are most likely on the hot path, in decreasing order of frequency
    case OnNext(id: Int, e: Any) ⇒
      if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onNext $e id=$id")
      inputs(id).onNext(e)
      runBatch()
    case RequestMore(id: Int, demand: Long) ⇒
      if (GraphInterpreter.Debug) println(s"${interpreter.Name}  request  $demand id=$id")
      outputs(id).requestMore(demand)
      runBatch()
    case Resume ⇒
      resumeScheduled = false
      if (interpreter.isSuspended) runBatch()
    case AsyncInput(logic, event, handler) ⇒
      if (GraphInterpreter.Debug) println(s"${interpreter.Name} ASYNC $event ($handler) [$logic]")
      if (!interpreter.isStageCompleted(logic)) {
        try handler(event)
        catch {
          case NonFatal(e) ⇒ logic.failStage(e)
        }
        interpreter.afterStageHasRun(logic)
      }
      runBatch()

    // Initialization and completion messages
    case OnError(id: Int, cause: Throwable) ⇒
      if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onError id=$id")
      inputs(id).onError(cause)
      runBatch()
    case OnComplete(id: Int) ⇒
      if (GraphInterpreter.Debug) println(s"${interpreter.Name}  onComplete id=$id")
      inputs(id).onComplete()
      runBatch()
    case OnSubscribe(id: Int, subscription: Subscription) ⇒
      subscribesPending -= 1
      inputs(id).onSubscribe(subscription)
    case Cancel(id: Int) ⇒
      if (GraphInterpreter.Debug) println(s"${interpreter.Name}  cancel id=$id")
      outputs(id).cancel()
      runBatch()
    case SubscribePending(id: Int) ⇒
      outputs(id).subscribePending()
    case ExposedPublisher(id, publisher) ⇒
      outputs(id).exposedPublisher(publisher)

  }

  private def waitShutdown: Receive = {
    case OnSubscribe(_, sub) ⇒
      tryCancel(sub)
      subscribesPending -= 1
      if (subscribesPending == 0) context.stop(self)
    case ReceiveTimeout ⇒
      tryAbort(new TimeoutException("Streaming actor has been already stopped processing (normally), but not all of its " +
        s"inputs have been subscribed in [${settings.subscriptionTimeoutSettings.timeout}}]. Aborting actor now."))
    case _ ⇒ // Ignore, there is nothing to do anyway
  }

  private def runBatch(): Unit = {
    try {
      val effectiveLimit = {
        if (!settings.fuzzingMode) eventLimit
        else {
          if (ThreadLocalRandom.current().nextBoolean()) Thread.`yield`()
          ThreadLocalRandom.current().nextInt(2) // 1 or zero events to be processed
        }
      }
      interpreter.execute(effectiveLimit)
      if (interpreter.isCompleted) {
        // Cannot stop right away if not completely subscribed
        if (subscribesPending == 0) context.stop(self)
        else {
          context.become(waitShutdown)
          context.setReceiveTimeout(settings.subscriptionTimeoutSettings.timeout)
        }
      } else if (interpreter.isSuspended && !resumeScheduled) {
        resumeScheduled = true
        self ! Resume
      }
    } catch {
      case NonFatal(e) ⇒
        context.stop(self)
        tryAbort(e)
    }
  }

  /**
   * Attempts to abort execution, by first propagating the reason given until either
   *  - the interpreter successfully finishes
   *  - the event limit is reached
   *  - a new error is encountered
   */
  private def tryAbort(ex: Throwable): Unit = {
    // This should handle termination while interpreter is running. If the upstream have been closed already this
    // call has no effect and therefore do the right thing: nothing.
    try {
      inputs.foreach(_.onInternalError(ex))
      interpreter.execute(abortLimit)
      interpreter.finish()
    } // Will only have an effect if the above call to the interpreter failed to emit a proper failure to the downstream
    // otherwise this will have no effect
    finally {
      outputs.foreach(_.fail(ex))
      inputs.foreach(_.cancel())
    }
  }

  override def postStop(): Unit = tryAbort(AbruptTerminationException(self))
}
