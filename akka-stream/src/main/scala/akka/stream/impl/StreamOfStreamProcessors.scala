/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import akka.actor.{ Terminated, ActorRef }
import org.reactivestreams.spi.{ Subscriber, Subscription }
import org.reactivestreams.api.Producer

/**
 * INTERNAL API
 */
private[akka] object MultiStreamOutputProcessor {
  case class SubstreamRequestMore(substream: ActorRef, demand: Int)
  case class SubstreamCancel(substream: ActorRef)

  class SubstreamSubscription(val parent: ActorRef, val substream: ActorRef) extends Subscription {
    override def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else parent ! SubstreamRequestMore(substream, elements)
    override def cancel(): Unit = parent ! SubstreamCancel(substream)
    override def toString = "SubstreamSubscription" + System.identityHashCode(this)
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamOutputProcessor(_settings: MaterializerSettings) extends ActorProcessorImpl(_settings) {
  import MultiStreamOutputProcessor._

  private val substreamOutputs = collection.mutable.Map.empty[ActorRef, SubstreamOutputs]

  class SubstreamOutputs extends Outputs {
    private var completed: Boolean = false
    private var demands: Int = 0

    val substream = context.watch(context.actorOf(IdentityProcessorImpl.props(settings)))
    val processor = new ActorProcessor[AnyRef, AnyRef](substream)

    override def isClosed: Boolean = completed
    override def complete(): Unit = {
      if (!completed) substream ! OnComplete
      completed = true
    }

    override def cancel(e: Throwable): Unit = {
      if (!completed) substream ! OnError(e)
      completed = true
    }

    override def enqueueOutputElement(elem: Any): Unit = {
      demands -= 1
      substream ! OnNext(elem)
    }

    def enqueueOutputDemand(demand: Int): Unit = demands += demand
    override def demandAvailable: Boolean = demands > 0
    override val NeedsDemand: TransferState = new TransferState {
      override def isReady: Boolean = demandAvailable
      override def isCompleted: Boolean = completed
    }
    override val NeedsDemandOrCancel: TransferState = new TransferState {
      override def isReady: Boolean = demandAvailable || isClosed
      override def isCompleted: Boolean = false
    }
  }

  protected def newSubstream(): SubstreamOutputs = {
    val outputs = new SubstreamOutputs
    outputs.substream ! OnSubscribe(new SubstreamSubscription(self, outputs.substream))
    substreamOutputs(outputs.substream) = outputs
    outputs
  }

  def fullyCompleted: Boolean = isShuttingDown && isPumpFinished && context.children.isEmpty

  protected def invalidateSubstream(substream: ActorRef): Unit = {
    substreamOutputs(substream).complete()
    substreamOutputs -= substream
    if (fullyCompleted) shutdown()
    pump()
  }

  override def fail(e: Throwable): Unit = {
    substreamOutputs.values foreach (_.cancel(e))
    super.fail(e)
  }

  override def primaryOutputsFinished(completed: Boolean): Unit = {
    // If the master stream is cancelled (no one consumes substreams as elements from the master stream)
    // then this callback does not mean we are shutting down
    // We can only shut down after all substreams (our children) are closed
    if (fullyCompleted) shutdown()
  }

  override def pumpFinished(): Unit = {
    context.children foreach (_ ! OnComplete)
    super.pumpFinished()
  }

  override val downstreamManagement: Receive = super.downstreamManagement orElse {
    case SubstreamRequestMore(key, demand) ⇒
      substreamOutputs(key).enqueueOutputDemand(demand)
      pump()
    case SubstreamCancel(key) ⇒ // FIXME: Terminated should handle this case. Maybe remove SubstreamCancel and just Poison self?
    case Terminated(child)    ⇒ invalidateSubstream(child)

  }
}

/**
 * INTERNAL API
 */
private[akka] object TwoStreamInputProcessor {
  class OtherActorSubscriber[T](val impl: ActorRef) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = impl ! OnError(cause)
    override def onComplete(): Unit = impl ! OtherStreamOnComplete
    override def onNext(element: T): Unit = impl ! OtherStreamOnNext(element)
    override def onSubscribe(subscription: Subscription): Unit = impl ! OtherStreamOnSubscribe(subscription)
  }

  case object OtherStreamOnComplete
  case class OtherStreamOnNext(element: Any)
  case class OtherStreamOnSubscribe(subscription: Subscription)
}

/**
 * INTERNAL API
 */
private[akka] abstract class TwoStreamInputProcessor(_settings: MaterializerSettings, val other: Producer[Any])
  extends ActorProcessorImpl(_settings) {
  import TwoStreamInputProcessor._

  var secondaryInputs: Inputs = _

  override def primaryOutputsReady(): Unit = {
    other.getPublisher.subscribe(new OtherActorSubscriber(self))
    super.primaryOutputsReady()
  }

  override def waitingForUpstream: Receive = super.waitingForUpstream orElse {
    case OtherStreamOnComplete ⇒
      secondaryInputs = EmptyInputs
      transitionToRunningWhenReady()
    case OtherStreamOnSubscribe(subscription) ⇒
      assert(subscription != null)
      secondaryInputs = new BatchingInputBuffer(subscription, settings.initialInputBufferSize)
      transitionToRunningWhenReady()
  }

  override def running: Receive = super.running orElse {
    case OtherStreamOnNext(element) ⇒
      secondaryInputs.enqueueInputElement(element)
      pump()
    case OtherStreamOnComplete ⇒
      secondaryInputs.complete()
      primaryInputOnComplete()
      pump()
  }

  override def primaryInputOnComplete(): Unit = {
    if (secondaryInputs.isClosed && primaryInputs.isClosed)
      super.primaryInputOnComplete()
  }

  override def transitionToRunningWhenReady(): Unit = if ((primaryInputs ne null) && (secondaryInputs ne null)) {
    secondaryInputs.prefetch()
    super.transitionToRunningWhenReady()
  }

  override def fail(cause: Throwable): Unit = {
    if (secondaryInputs ne null) secondaryInputs.cancel()
    super.fail(cause)
  }

  override def primaryOutputsFinished(completed: Boolean) {
    if (secondaryInputs ne null) secondaryInputs.cancel()
    super.primaryOutputsFinished(completed)
  }

}