/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.Arrays
import akka.actor.{ Actor, ActorRef }
import akka.event.Logging
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.actor.ActorSubscriber.OnSubscribe
import akka.stream.actor.ActorSubscriberMessage.{ OnNext, OnError, OnComplete }
import akka.stream.impl._
import akka.stream.stage._
import org.reactivestreams.{ Subscriber, Subscription }
import scala.util.control.NonFatal
import akka.actor.Props
import akka.actor.ActorLogging
import akka.event.LoggingAdapter
import akka.actor.DeadLetterSuppression
import akka.stream.ActorFlowMaterializer

/**
 * INTERNAL API
 */
private[akka] class BatchingActorInputBoundary(val size: Int, val name: String)
  extends BoundaryStage {

  require(size > 0, "buffer size cannot be zero")
  require((size & (size - 1)) == 0, "buffer size must be a power of two")

  // TODO: buffer and batch sizing heuristics
  private var upstream: Subscription = _
  private val inputBuffer = Array.ofDim[AnyRef](size)
  private var inputBufferElements = 0
  private var nextInputElementCursor = 0
  private var upstreamCompleted = false
  private var downstreamWaiting = false
  private var downstreamCanceled = false
  private val IndexMask = size - 1

  private def requestBatchSize = math.max(1, inputBuffer.length / 2)
  private var batchRemaining = requestBatchSize

  val subreceive: SubReceive = new SubReceive(waitingForUpstream)

  def isFinished = upstreamCompleted && ((upstream ne null) || downstreamCanceled)

  def setDownstreamCanceled(): Unit = downstreamCanceled = true

  private def dequeue(): Any = {
    val elem = inputBuffer(nextInputElementCursor)
    assert(elem ne null)
    inputBuffer(nextInputElementCursor) = null

    batchRemaining -= 1
    if (batchRemaining == 0 && !upstreamCompleted) {
      upstream.request(requestBatchSize)
      batchRemaining = requestBatchSize
    }

    inputBufferElements -= 1
    nextInputElementCursor = (nextInputElementCursor + 1) & IndexMask
    elem
  }

  private def enqueue(elem: Any): Unit = {
    if (OneBoundedInterpreter.Debug) println(f" enq $elem%-19s $name")
    if (!upstreamCompleted) {
      if (inputBufferElements == size) throw new IllegalStateException("Input buffer overrun")
      inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
      inputBufferElements += 1
    }
  }

  override def onPush(elem: Any, ctx: BoundaryContext): Directive =
    throw new UnsupportedOperationException("BUG: Cannot push the upstream boundary")

  override def onPull(ctx: BoundaryContext): Directive = {
    if (inputBufferElements > 1) ctx.push(dequeue())
    else if (inputBufferElements == 1) {
      if (upstreamCompleted) ctx.pushAndFinish(dequeue())
      else ctx.push(dequeue())
    } else if (upstreamCompleted) {
      ctx.finish()
    } else {
      downstreamWaiting = true
      ctx.exit()
    }
  }

  override def onDownstreamFinish(ctx: BoundaryContext): TerminationDirective = {
    cancel()
    ctx.finish()
  }

  def cancel(): Unit = {
    if (!upstreamCompleted) {
      upstreamCompleted = true
      if (upstream ne null) upstream.cancel()
      downstreamWaiting = false
      clear()
    }
  }

  private def clear(): Unit = {
    Arrays.fill(inputBuffer, 0, inputBuffer.length, null)
    inputBufferElements = 0
  }

  private def onComplete(): Unit =
    if (!upstreamCompleted) {
      upstreamCompleted = true
      // onUpstreamFinish is not back-pressured, stages need to deal with this
      if (inputBufferElements == 0) enterAndFinish()
    }

  private def onSubscribe(subscription: Subscription): Unit = {
    assert(subscription != null)
    if (upstreamCompleted)
      subscription.cancel()
    else if (downstreamCanceled) {
      upstreamCompleted = true
      subscription.cancel()
    } else {
      upstream = subscription
      // Prefetch
      upstream.request(inputBuffer.length)
      subreceive.become(upstreamRunning)
    }
  }

  private def onError(e: Throwable): Unit = {
    upstreamCompleted = true
    enterAndFail(e)
  }

  private def waitingForUpstream: Actor.Receive = {
    case OnComplete                ⇒ onComplete()
    case OnSubscribe(subscription) ⇒ onSubscribe(subscription)
    case OnError(cause)            ⇒ onError(cause)
  }

  private def upstreamRunning: Actor.Receive = {
    case OnNext(element) ⇒
      enqueue(element)
      if (downstreamWaiting) {
        downstreamWaiting = false
        enterAndPush(dequeue())
      }

    case OnComplete                ⇒ onComplete()
    case OnError(cause)            ⇒ onError(cause)
    case OnSubscribe(subscription) ⇒ subscription.cancel() // spec rule 2.5
  }

}

private[akka] object ActorOutputBoundary {
  /**
   * INTERNAL API.
   */
  private case object ContinuePulling extends DeadLetterSuppression
}

/**
 * INTERNAL API
 */
private[akka] class ActorOutputBoundary(val actor: ActorRef,
                                        val debugLogging: Boolean,
                                        val log: LoggingAdapter,
                                        val outputBurstLimit: Int)
  extends BoundaryStage {
  import ReactiveStreamsCompliance._
  import ActorOutputBoundary._

  private var exposedPublisher: ActorPublisher[Any] = _

  private var subscriber: Subscriber[Any] = _
  private var downstreamDemand: Long = 0L
  // This flag is only used if complete/fail is called externally since this op turns into a Finished one inside the
  // interpreter (i.e. inside this op this flag has no effects since if it is completed the op will not be invoked)
  private var downstreamCompleted = false
  // this is true while we “hold the ball”; while “false” incoming demand will just be queued up
  private var upstreamWaiting = true
  // the number of elements emitted during a single execution is bounded
  private var burstRemaining = outputBurstLimit

  private def tryBounceBall(ctx: BoundaryContext) = {
    burstRemaining -= 1
    if (burstRemaining > 0) ctx.pull()
    else {
      actor ! ContinuePulling
      takeBallOut(ctx)
    }
  }

  private def takeBallOut(ctx: BoundaryContext) = {
    upstreamWaiting = true
    ctx.exit()
  }

  private def tryPutBallIn() =
    if (upstreamWaiting) {
      burstRemaining = outputBurstLimit
      upstreamWaiting = false
      enterAndPull()
    }

  val subreceive = new SubReceive(waitingExposedPublisher)

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
      if (debugLogging)
        log.debug("fail due to: {}", e.getMessage)
      if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
      if ((subscriber ne null) && !e.isInstanceOf[SpecViolation]) tryOnError(subscriber, e)
    }
  }

  override def onPush(elem: Any, ctx: BoundaryContext): Directive = {
    onNext(elem)
    if (downstreamCompleted) ctx.finish()
    else if (downstreamDemand > 0) tryBounceBall(ctx)
    else takeBallOut(ctx)
  }

  override def onPull(ctx: BoundaryContext): Directive =
    throw new UnsupportedOperationException("BUG: Cannot pull the downstream boundary")

  override def onUpstreamFinish(ctx: BoundaryContext): TerminationDirective = {
    complete()
    ctx.finish()
  }

  override def onUpstreamFailure(cause: Throwable, ctx: BoundaryContext): TerminationDirective = {
    fail(cause)
    ctx.fail(cause)
  }

  private def subscribePending(subscribers: Seq[Subscriber[Any]]): Unit =
    subscribers foreach { sub ⇒
      if (subscriber eq null) {
        subscriber = sub
        tryOnSubscribe(subscriber, new ActorSubscription(actor, subscriber))
      } else
        rejectAdditionalSubscriber(subscriber, s"${Logging.simpleName(this)}")
    }

  protected def waitingExposedPublisher: Actor.Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      subreceive.become(downstreamRunning)
    case other ⇒
      throw new IllegalStateException(s"The first message must be ExposedPublisher but was [$other]")
  }

  protected def downstreamRunning: Actor.Receive = {
    case SubscribePending ⇒
      subscribePending(exposedPublisher.takePendingSubscribers())
    case RequestMore(subscription, elements) ⇒
      if (elements < 1) {
        enterAndFinish()
        fail(ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
      } else {
        downstreamDemand += elements
        if (downstreamDemand < 0)
          downstreamDemand = Long.MaxValue // Long overflow, Reactive Streams Spec 3:17: effectively unbounded
        if (OneBoundedInterpreter.Debug) {
          val s = s"$downstreamDemand (+$elements)"
          println(f" dem $s%-19s ${actor.path}")
        }
        tryPutBallIn()
      }

    case ContinuePulling ⇒
      if (!downstreamCompleted && downstreamDemand > 0) tryPutBallIn()

    case Cancel(subscription) ⇒
      downstreamCompleted = true
      subscriber = null
      exposedPublisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      enterAndFinish()
  }

}

/**
 * INTERNAL API
 */
private[akka] object ActorInterpreter {
  def props(settings: ActorFlowMaterializerSettings, ops: Seq[Stage[_, _]], materializer: ActorFlowMaterializer): Props =
    Props(new ActorInterpreter(settings, ops, materializer))

  case class AsyncInput(op: AsyncStage[Any, Any, Any], ctx: AsyncContext[Any, Any], event: Any) extends DeadLetterSuppression
}

/**
 * INTERNAL API
 */
private[akka] class ActorInterpreter(val settings: ActorFlowMaterializerSettings, val ops: Seq[Stage[_, _]], val materializer: ActorFlowMaterializer)
  extends Actor with ActorLogging {
  import ActorInterpreter._

  private val upstream = new BatchingActorInputBoundary(settings.initialInputBufferSize, context.self.path.toString)
  private val downstream = new ActorOutputBoundary(self, settings.debugLogging, log, settings.outputBurstLimit)
  private val interpreter =
    new OneBoundedInterpreter(upstream +: ops :+ downstream,
      (op, ctx, event) ⇒ self ! AsyncInput(op, ctx, event),
      materializer,
      name = context.self.path.toString)
  interpreter.init()

  def receive: Receive = upstream.subreceive.orElse[Any, Unit](downstream.subreceive).orElse[Any, Unit] {
    case AsyncInput(op, ctx, event) ⇒
      ctx.enter()
      op.onAsyncInput(event, ctx)
      ctx.execute()
  }

  override protected[akka] def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    super.aroundReceive(receive, msg)

    if (interpreter.isFinished) {
      if (upstream.isFinished) context.stop(self)
      else upstream.setDownstreamCanceled()
    }
  }

  override def postStop(): Unit = {
    upstream.cancel()
    downstream.fail(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted", reason)
  }

}
