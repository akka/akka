/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.Arrays
import scala.collection.immutable
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal
import org.reactivestreams.api.Processor
import org.reactivestreams.spi.{ Subscriber, Subscription }
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.stream.GeneratorSettings

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {
  import Ast._
  def props(settings: GeneratorSettings, op: AstNode): Props = op match {
    case t: Transform ⇒ Props(new TransformProcessorImpl(settings, t))
    case r: Recover   ⇒ Props(new RecoverProcessorImpl(settings, r))
  }
}

class ActorProcessor[I, O]( final val impl: ActorRef) extends Processor[I, O] with ActorConsumerLike[I] with ActorProducerLike[O]

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: GeneratorSettings) extends Actor with SubscriberManagement[Any] with ActorLogging {
  import ActorProcessor._
  type S = ActorSubscription

  override def maxBufferSize: Int = settings.maxFanOutBufferSize
  override def initialBufferSize: Int = settings.initialFanOutBufferSize
  override def createSubscription(subscriber: Subscriber[Any]): S = new ActorSubscription(self, subscriber)

  override def receive = waitingExposedPublisher

  //////////////////////  Startup phases //////////////////////

  var upstream: Subscription = _
  var exposedPublisher: ActorPublisher[Any] = _

  def waitingExposedPublisher: Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.become(waitingForUpstream)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForUpstream: Receive = downstreamManagement orElse {
    case OnComplete ⇒ shutdown() // There is nothing to flush here
    case OnSubscribe(subscription) ⇒
      assert(subscription != null)
      upstream = subscription
      // Prime up input buffer
      upstream.requestMore(inputBuffer.length)
      context.become(running)
    case OnError(cause) ⇒ failureReceived(cause)
  }

  //////////////////////  Management of subscribers //////////////////////

  // All methods called here are implemented by SubscriberManagement
  val downstreamManagement: Receive = {
    case SubscribePending ⇒
      subscribePending()
    case RequestMore(subscription, elements) ⇒
      moreRequested(subscription, elements)
      pump()
    case Cancel(subscription) ⇒
      unregisterSubscription(subscription)
      pump()
  }

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  //////////////////////  Active state //////////////////////

  def running: Receive = downstreamManagement orElse {
    case OnNext(element) ⇒
      enqueueInputElement(element)
      pump()
    case OnComplete ⇒
      flushAndComplete()
      pump()
    case OnError(cause) ⇒ failureReceived(cause)
  }

  // Called by SubscriberManagement when all subscribers are gone.
  // The method shutdown() is called automatically by SubscriberManagement after it called this method.
  override def cancelUpstream(): Unit = if (upstream ne null) upstream.cancel()

  // Called by SubscriberManagement whenever the output buffer is ready to accept additional elements
  override protected def requestFromUpstream(elements: Int): Unit = {
    downstreamBufferSpace += elements
  }

  def failureReceived(e: Throwable): Unit = fail(e)

  def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    abortDownstream(e)
    if (upstream ne null) upstream.cancel()
    context.stop(self)
  }

  private var downstreamBufferSpace = 0
  private var inputBuffer = Array.ofDim[AnyRef](settings.initialInputBufferSize)
  private var inputBufferElements = 0
  private var nextInputElementCursor = 0
  val IndexMask = settings.initialInputBufferSize - 1

  // TODO: buffer and batch sizing heuristics
  def requestBatchSize = math.max(1, inputBuffer.length / 2)
  private var batchRemaining = requestBatchSize

  def dequeueInputElement(): Any = {
    val elem = inputBuffer(nextInputElementCursor)
    inputBuffer(nextInputElementCursor) = null

    batchRemaining -= 1
    if (batchRemaining == 0 && !upstreamCompleted) {
      upstream.requestMore(requestBatchSize)
      batchRemaining = requestBatchSize
    }

    inputBufferElements -= 1
    nextInputElementCursor += 1
    nextInputElementCursor &= IndexMask
    elem
  }

  def enqueueInputElement(elem: Any): Unit = {
    inputBuffer((nextInputElementCursor + inputBufferElements) & IndexMask) = elem.asInstanceOf[AnyRef]
    inputBufferElements += 1
  }

  def enqueueOutputElement(elem: Any): Unit = {
    downstreamBufferSpace -= 1
    pushToDownstream(elem)
  }

  // States of the operation that is executed by this processor
  trait TransferState {
    protected def isReady: Boolean
    def isCompleted: Boolean
    def isExecutable = isReady && !isCompleted
    def inputsAvailable = inputBufferElements > 0
    def demandAvailable = downstreamBufferSpace > 0
    def inputsDepleted = upstreamCompleted && inputBufferElements == 0
  }
  object NeedsInput extends TransferState {
    def isReady = inputsAvailable || inputsDepleted
    def isCompleted = false
  }
  object NeedsDemand extends TransferState {
    def isReady = demandAvailable
    def isCompleted = false
  }
  object NeedsInputAndDemand extends TransferState {
    def isReady = inputsAvailable && demandAvailable || inputsDepleted
    def isCompleted = false
  }
  object Completed extends TransferState {
    def isReady = false
    def isCompleted = true
  }

  var transferState: TransferState = NeedsInputAndDemand

  // Exchange input buffer elements and output buffer "requests" until one of them becomes empty.
  // Generate upstream requestMore for every Nth consumed input element
  protected def pump(): Unit = {
    try while (transferState.isExecutable) {
      transferState = transfer(transferState)
    } catch { case NonFatal(e) ⇒ fail(e) }

    if (transferState.isCompleted) {
      if (!isShuttingDown) {
        if (!upstreamCompleted) upstream.cancel()
        Arrays.fill(inputBuffer, nextInputElementCursor, nextInputElementCursor + inputBufferElements, null)
        inputBufferElements = 0
        context.become(flushing)
        isShuttingDown = true
      }
      completeDownstream()
    }
  }

  // Needs to be implemented by Processor implementations. Transfers elements from the input buffer to the output
  // buffer.
  protected def transfer(current: TransferState): TransferState

  //////////////////////  Completing and Flushing  //////////////////////

  var upstreamCompleted = false

  protected def flushAndComplete(): Unit = {
    upstreamCompleted = true
    context.become(flushing)
  }

  def flushing: Receive = downstreamManagement orElse {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    case _                         ⇒ // ignore everything else
  }

  //////////////////////  Shutdown and cleanup (graceful and abort) //////////////////////

  var isShuttingDown = false

  // Called by SubscriberManagement to signal that output buffer finished (flushed or aborted)
  override def shutdown(): Unit = {
    isShuttingDown = true
    context.stop(self)
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown()
    // Non-gracefully stopped, do our best here
    if (!isShuttingDown)
      abortDownstream(new IllegalStateException("Processor actor terminated abruptly"))

    // FIXME what about upstream subscription before we got 
    // case OnSubscribe(subscription) ⇒ subscription.cancel()  
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

}

/**
 * INTERNAL API
 */
private[akka] class TransformProcessorImpl(_settings: GeneratorSettings, op: Ast.Transform) extends ActorProcessorImpl(_settings) {
  var state = op.zero
  var isComplete = false
  var hasOnCompleteRun = false
  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]

  override def transfer(current: TransferState): TransferState = {
    val depleted = current.inputsDepleted
    if (emits.isEmpty) {
      isComplete = op.isComplete(state)
      if (depleted || isComplete) {
        emits = op.onComplete(state)
        hasOnCompleteRun = true
      } else {
        val e = dequeueInputElement()
        val (nextState, newEmits) = op.f(state, e)
        state = nextState
        emits = newEmits
      }
    } else {
      enqueueOutputElement(emits.head)
      emits = emits.tail
    }

    if (emits.nonEmpty) NeedsDemand
    else if (hasOnCompleteRun) Completed
    else NeedsInputAndDemand
  }

  override def toString: String = s"Transformer(state=$state, isComplete=$isComplete, hasOnCompleteRun=$hasOnCompleteRun, emits=$emits)"
}

/**
 * INTERNAL API
 */
private[akka] class RecoverProcessorImpl(_settings: GeneratorSettings, _op: Ast.Recover) extends TransformProcessorImpl(_settings, _op.t) {
  override def enqueueInputElement(elem: Any): Unit = {
    super.enqueueInputElement(Success(elem))
  }
  override def failureReceived(e: Throwable): Unit = {
    super.enqueueInputElement(Failure(e))
    flushAndComplete()
    pump()
  }
}
