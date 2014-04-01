/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal
import org.reactivestreams.api.Processor
import org.reactivestreams.spi.Subscriber
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.stream.GeneratorSettings
import akka.event.LoggingReceive

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {
  import Ast._
  def props(settings: GeneratorSettings, op: AstNode): Props = op match {
    case t: Transform ⇒ Props(new TransformProcessorImpl(settings, t))
    case r: Recover   ⇒ Props(new RecoverProcessorImpl(settings, r))
    case s: SplitWhen ⇒ Props(new SplitWhenProcessorImpl(settings, s.p))
    case g: GroupBy   ⇒ Props(new GroupByProcessorImpl(settings, g.f))
    case m: Merge     ⇒ Props(new MergeImpl(settings, m.other))
    case z: Zip       ⇒ Props(new ZipImpl(settings, z.other))
    case c: Concat    ⇒ Props(new ConcatImpl(settings, c.next))
  }
}

class ActorProcessor[I, O]( final val impl: ActorRef) extends Processor[I, O] with ActorConsumerLike[I] with ActorProducerLike[O]

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: GeneratorSettings) extends Actor with SubscriberManagement[Any] with ActorLogging {
  type S = ActorSubscription[Any]

  override def maxBufferSize: Int = settings.maxFanOutBufferSize
  override def initialBufferSize: Int = settings.initialFanOutBufferSize
  override def createSubscription(subscriber: Subscriber[Any]): S = new ActorSubscription(self, subscriber)

  override def receive = waitingExposedPublisher

  protected var primaryInputs: Inputs = _

  //////////////////////  Startup phases //////////////////////

  var exposedPublisher: ActorPublisher[Any] = _

  def waitingExposedPublisher: Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.become(waitingForUpstream)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForUpstream: Receive = downstreamManagement orElse {
    case OnComplete ⇒
      // Instead of introducing an edge case, handle it in the general way
      primaryInputs = EmptyInputs
      transitionToRunningWhenReady()
    case OnSubscribe(subscription) ⇒
      assert(subscription != null)
      primaryInputs = new BatchingInputBuffer(subscription, settings.initialInputBufferSize)
      transitionToRunningWhenReady()
    case OnError(cause) ⇒ failureReceived(cause)
  }

  def transitionToRunningWhenReady(): Unit =
    if (primaryInputs ne null) {
      primaryInputs.prefetch()
      transferState = initialTransferState
      context.become(running)
    }

  //////////////////////  Management of subscribers //////////////////////

  // All methods called here are implemented by SubscriberManagement
  def downstreamManagement: Receive = {
    case SubscribePending ⇒
      subscribePending()
    case RequestMore(subscription, elements) ⇒
      moreRequested(subscription.asInstanceOf[S], elements)
      pump()
    case Cancel(subscription) ⇒
      unregisterSubscription(subscription.asInstanceOf[S])
      pump()
  }

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  //////////////////////  Active state //////////////////////

  def running: Receive = LoggingReceive(downstreamManagement orElse {
    case OnNext(element) ⇒
      primaryInputs.enqueueInputElement(element)
      pump()
    case OnComplete ⇒
      primaryInputs.complete()
      flushAndComplete()
      pump()
    case OnError(cause) ⇒ failureReceived(cause)
  })

  // Called by SubscriberManagement when all subscribers are gone.
  // The method shutdown() is called automatically by SubscriberManagement after it called this method.
  override def cancelUpstream(): Unit = {
    if (primaryInputs ne null) primaryInputs.cancel()
    PrimaryOutputs.cancel()
  }

  // Called by SubscriberManagement whenever the output buffer is ready to accept additional elements
  override protected def requestFromUpstream(elements: Int): Unit = {
    // FIXME: Remove debug logging
    log.debug(s"received downstream demand from buffer: $elements")
    PrimaryOutputs.enqueueOutputDemand(elements)
  }

  def failureReceived(e: Throwable): Unit = fail(e)

  def fail(e: Throwable): Unit = {
    shutdownReason = Some(e)
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    abortDownstream(e)
    if (primaryInputs ne null) primaryInputs.cancel()
    context.stop(self)
  }

  object PrimaryOutputs extends Outputs {
    private var downstreamBufferSpace = 0
    private var downstreamCompleted = false
    def demandAvailable = downstreamBufferSpace > 0

    def enqueueOutputDemand(demand: Int): Unit = downstreamBufferSpace += demand
    def enqueueOutputElement(elem: Any): Unit = {
      downstreamBufferSpace -= 1
      pushToDownstream(elem)
    }

    def complete(): Unit = downstreamCompleted = true
    def cancel(): Unit = downstreamCompleted = true
    def isClosed: Boolean = downstreamCompleted
    override val NeedsDemand: TransferState = new TransferState {
      def isReady = demandAvailable
      def isCompleted = downstreamCompleted
    }
    override def NeedsDemandOrCancel: TransferState = new TransferState {
      def isReady = demandAvailable || downstreamCompleted
      def isCompleted = false
    }
  }

  lazy val needsPrimaryInputAndDemand = primaryInputs.NeedsInput && PrimaryOutputs.NeedsDemand

  private var transferState: TransferState = NotInitialized
  protected def setTransferState(t: TransferState): Unit = transferState = t
  protected def initialTransferState: TransferState

  // Exchange input buffer elements and output buffer "requests" until one of them becomes empty.
  // Generate upstream requestMore for every Nth consumed input element
  protected def pump(): Unit = {
    try while (transferState.isExecutable) {
      // FIXME: Remove debug logging
      log.debug(s"iterating the pump with state $transferState and buffer $bufferDebug")
      transferState = transfer()
    } catch { case NonFatal(e) ⇒ fail(e) }

    // FIXME: Remove debug logging
    log.debug(s"finished iterating the pump with state $transferState and buffer $bufferDebug")

    if (transferState.isCompleted) {
      if (!isShuttingDown) {
        // FIXME: Remove debug logging
        log.debug("shutting down the pump")
        if (primaryInputs.isOpen) primaryInputs.cancel()
        primaryInputs.clear()
        context.become(flushing)
        isShuttingDown = true
      }
      completeDownstream()
    }
  }

  // Needs to be implemented by Processor implementations. Transfers elements from the input buffer to the output
  // buffer.
  protected def transfer(): TransferState

  //////////////////////  Completing and Flushing  //////////////////////

  protected def flushAndComplete(): Unit = context.become(flushing)

  def flushing: Receive = downstreamManagement orElse {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    case _                         ⇒ // ignore everything else
  }

  //////////////////////  Shutdown and cleanup (graceful and abort) //////////////////////

  var isShuttingDown = false

  var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  // Called by SubscriberManagement to signal that output buffer finished (flushed or aborted)
  override def shutdown(completed: Boolean): Unit = {
    isShuttingDown = true
    if (completed)
      shutdownReason = None
    PrimaryOutputs.complete()
    context.stop(self)
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(shutdownReason)
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

  object NeedsInputAndDemandOrCompletion extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && PrimaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    def isCompleted = false
  }

  override def initialTransferState = NeedsInputAndDemandOrCompletion

  override def transfer(): TransferState = {
    val depleted = primaryInputs.inputsDepleted
    if (emits.isEmpty) {
      isComplete = op.isComplete(state)
      if (depleted || isComplete) {
        emits = op.onComplete(state)
        hasOnCompleteRun = true
      } else {
        val e = primaryInputs.dequeueInputElement()
        val (nextState, newEmits) = op.f(state, e)
        state = nextState
        emits = newEmits
      }
    } else {
      PrimaryOutputs.enqueueOutputElement(emits.head)
      emits = emits.tail
    }

    if (emits.nonEmpty) PrimaryOutputs.NeedsDemand
    else if (hasOnCompleteRun) Completed
    else NeedsInputAndDemandOrCompletion
  }

  override def toString: String = s"Transformer(state=$state, isComplete=$isComplete, hasOnCompleteRun=$hasOnCompleteRun, emits=$emits)"
}

/**
 * INTERNAL API
 */
private[akka] class RecoverProcessorImpl(_settings: GeneratorSettings, _op: Ast.Recover) extends TransformProcessorImpl(_settings, _op.t) {

  val wrapInSuccess: Receive = {
    case OnNext(elem) ⇒
      primaryInputs.enqueueInputElement(Success(elem))
      pump()
  }

  override def running: Receive = wrapInSuccess orElse super.running

  override def failureReceived(e: Throwable): Unit = {
    primaryInputs.enqueueInputElement(Failure(e))
    primaryInputs.complete()
    flushAndComplete()
    pump()
  }
}

/**
 * INTERNAL API
 */
private[akka] object IdentityProcessorImpl {
  def props(settings: GeneratorSettings): Props = Props(new IdentityProcessorImpl(settings))
}

/**
 * INTERNAL API
 */
private[akka] class IdentityProcessorImpl(_settings: GeneratorSettings) extends ActorProcessorImpl(_settings) {

  override def initialTransferState = needsPrimaryInputAndDemand
  override protected def transfer(): TransferState = {
    PrimaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
    needsPrimaryInputAndDemand
  }

}
