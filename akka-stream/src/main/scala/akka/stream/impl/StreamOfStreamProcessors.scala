/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import akka.actor.{ Actor, Terminated, ActorRef }
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

    override def receive: SubReceive =
      throw new UnsupportedOperationException("Substream outputs are managed in a dedicated receive block")

    val substream = context.watch(context.actorOf(
      IdentityProcessorImpl.props(settings)
        .withDispatcher(context.props.dispatcher)))
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

  def fullyCompleted: Boolean = primaryOutputsShutdown && isPumpFinished && context.children.isEmpty

  protected def invalidateSubstream(substream: ActorRef): Unit = {
    substreamOutputs(substream).complete()
    substreamOutputs -= substream
    shutdownHooks()
    pump()
  }

  override def fail(e: Throwable): Unit = {
    substreamOutputs.values foreach (_.cancel(e))
    super.fail(e)
  }

  // FIXME: proper shutdown scheduling
  override def shutdownHooks(): Unit = if (fullyCompleted) super.shutdownHooks()

  override def pumpFinished(): Unit = {
    context.children foreach (_ ! OnComplete)
    super.pumpFinished()
  }

  val substreamManagement: Receive = {
    case SubstreamRequestMore(key, demand) ⇒
      substreamOutputs(key).enqueueOutputDemand(demand)
      pump()
    case SubstreamCancel(key) ⇒ // FIXME: Terminated should handle this case. Maybe remove SubstreamCancel and just Poison self?
    case Terminated(child)    ⇒ invalidateSubstream(child)

  }

  override def receive = primaryInputs.subreceive orElse primaryOutputs.receive orElse substreamManagement
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

  val secondaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, this) {
    override val subreceive: SubReceive = new SubReceive(waitingForUpstream)

    override def inputOnError(e: Throwable): Unit = TwoStreamInputProcessor.this.onError(e)

    override def waitingForUpstream: Receive = {
      case OtherStreamOnComplete                ⇒ onComplete()
      case OtherStreamOnSubscribe(subscription) ⇒ onSubscribe(subscription)
    }

    override def upstreamRunning: Receive = {
      case OtherStreamOnNext(element) ⇒ enqueueInputElement(element)
      case OtherStreamOnComplete      ⇒ onComplete()
    }
    override protected def completed: Actor.Receive = {
      case OtherStreamOnSubscribe(_) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    }
  }

  override def receive = secondaryInputs.subreceive orElse primaryInputs.subreceive orElse primaryOutputs.receive

  other.getPublisher.subscribe(new OtherActorSubscriber(self))

  override def shutdownHooks(): Unit = {
    secondaryInputs.cancel()
    super.shutdownHooks()
  }
}