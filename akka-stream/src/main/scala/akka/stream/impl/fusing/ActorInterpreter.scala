/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.Arrays
import akka.actor.{ Actor, ActorRef }
import akka.event.Logging
import akka.stream.MaterializerSettings
import akka.stream.actor.ActorSubscriber.OnSubscribe
import akka.stream.actor.ActorSubscriberMessage.{ OnNext, OnError, OnComplete }
import akka.stream.impl._
import akka.stream.stage._
import org.reactivestreams.{ Subscriber, Subscription }
import scala.util.control.NonFatal
import akka.actor.Props

/**
 * INTERNAL API
 */
private[akka] class BatchingActorInputBoundary(val size: Int) extends BoundaryStage {
  require(size > 0, "buffer size cannot be zero")
  require((size & (size - 1)) == 0, "buffer size must be a power of two")

  // TODO: buffer and batch sizing heuristics
  private var upstream: Subscription = _
  private val inputBuffer = Array.ofDim[AnyRef](size)
  private var inputBufferElements = 0
  private var nextInputElementCursor = 0
  private var upstreamCompleted = false
  private var downstreamWaiting = false
  private val IndexMask = size - 1

  private def requestBatchSize = math.max(1, inputBuffer.length / 2)
  private var batchRemaining = requestBatchSize

  val subreceive: SubReceive = new SubReceive(waitingForUpstream)

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
    ctx.exit()
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

  private def onComplete(): Unit = {
    upstreamCompleted = true
    subreceive.become(completed)
    if (inputBufferElements == 0) enter().finish()
  }

  private def onSubscribe(subscription: Subscription): Unit = {
    assert(subscription != null)
    upstream = subscription
    // Prefetch
    upstream.request(inputBuffer.length)
    subreceive.become(upstreamRunning)
  }

  private def onError(e: Throwable): Unit = {
    upstreamCompleted = true
    subreceive.become(completed)
    enter().fail(e)
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
        enter().push(dequeue())
      }

    case OnComplete                ⇒ onComplete()
    case OnError(cause)            ⇒ onError(cause)
    case OnSubscribe(subscription) ⇒ subscription.cancel() // spec rule 2.5
  }

  private def completed: Actor.Receive = {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
  }

}

/**
 * INTERNAL API
 */
private[akka] class ActorOutputBoundary(val actor: ActorRef) extends BoundaryStage {

  private var exposedPublisher: ActorPublisher[Any] = _

  private var subscriber: Subscriber[Any] = _
  private var downstreamDemand: Long = 0L
  // This flag is only used if complete/fail is called externally since this op turns into a Finished one inside the
  // interpreter (i.e. inside this op this flag has no effects since if it is completed the op will not be invoked)
  private var downstreamCompleted = false
  private var upstreamWaiting = true

  val subreceive = new SubReceive(waitingExposedPublisher)

  private def onNext(elem: Any): Unit = {
    downstreamDemand -= 1
    subscriber.onNext(elem)
  }

  private def complete(): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      if (subscriber ne null) subscriber.onComplete()
      if (exposedPublisher ne null) exposedPublisher.shutdown(None)
    }
  }

  def fail(e: Throwable): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      if (subscriber ne null) subscriber.onError(e)
      if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
    }
  }

  override def onPush(elem: Any, ctx: BoundaryContext): Directive = {
    onNext(elem)
    if (downstreamDemand > 0) ctx.pull()
    else if (downstreamCompleted) ctx.finish()
    else {
      upstreamWaiting = true
      ctx.exit()
    }
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
        subscriber.onSubscribe(new ActorSubscription(actor, subscriber))
      } else sub.onError(new IllegalStateException(s"${Logging.simpleName(this)} ${ReactiveStreamsCompliance.SupportsOnlyASingleSubscriber}"))
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
        enter().finish()
        fail(ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
      } else {
        downstreamDemand += elements
        // Long has overflown
        if (downstreamDemand < 0) {
          enter().finish()
          fail(ReactiveStreamsCompliance.totalPendingDemandMustNotExceedLongMaxValueException)
        } else if (upstreamWaiting) {
          upstreamWaiting = false
          enter().pull()
        }
      }

    case Cancel(subscription) ⇒
      downstreamCompleted = true
      subscriber = null
      exposedPublisher.shutdown(Some(new ActorPublisher.NormalShutdownException))
      enter().finish()
  }

}

/**
 * INTERNAL API
 */
private[akka] object ActorInterpreter {
  def props(settings: MaterializerSettings, ops: Seq[Stage[_, _]]): Props =
    Props(new ActorInterpreter(settings, ops))
}

/**
 * INTERNAL API
 */
private[akka] class ActorInterpreter(val settings: MaterializerSettings, val ops: Seq[Stage[_, _]])
  extends Actor {

  private val upstream = new BatchingActorInputBoundary(settings.initialInputBufferSize)
  private val downstream = new ActorOutputBoundary(self)
  private val interpreter = new OneBoundedInterpreter(upstream +: ops :+ downstream)
  interpreter.init()

  def receive: Receive = upstream.subreceive orElse downstream.subreceive

  override protected[akka] def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    super.aroundReceive(receive, msg)
    if (interpreter.isFinished) context.stop(self)
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
