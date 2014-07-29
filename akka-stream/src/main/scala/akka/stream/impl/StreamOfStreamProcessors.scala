/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import akka.actor.{ Actor, Terminated, ActorRef }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import akka.stream.actor.ActorSubscriber.{ OnNext, OnError, OnComplete, OnSubscribe }

/**
 * INTERNAL API
 */
private[akka] object MultiStreamOutputProcessor {
  case class SubstreamRequestMore(substream: ActorRef, demand: Int)
  case class SubstreamCancel(substream: ActorRef)

  class SubstreamSubscription(val parent: ActorRef, val substream: ActorRef) extends Subscription {
    override def request(elements: Int): Unit =
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

    override def subreceive: SubReceive =
      throw new UnsupportedOperationException("Substream outputs are managed in a dedicated receive block")

    val substream = context.watch(context.actorOf(
      IdentityProcessorImpl.props(settings)
        .withDispatcher(context.props.dispatcher)))
    val processor = ActorProcessor[AnyRef, AnyRef](substream)

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

  override def receive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse substreamManagement
}

/**
 * INTERNAL API
 */
private[akka] object TwoStreamInputProcessor {
  class OtherActorSubscriber[T](val impl: ActorRef) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = impl ! OtherStreamOnError(cause)
    override def onComplete(): Unit = impl ! OtherStreamOnComplete
    override def onNext(element: T): Unit = impl ! OtherStreamOnNext(element)
    override def onSubscribe(subscription: Subscription): Unit = impl ! OtherStreamOnSubscribe(subscription)
  }

  case object OtherStreamOnComplete
  case class OtherStreamOnNext(element: Any)
  case class OtherStreamOnSubscribe(subscription: Subscription)
  case class OtherStreamOnError(ex: Throwable)
}

/**
 * INTERNAL API
 */
private[akka] abstract class TwoStreamInputProcessor(_settings: MaterializerSettings, val other: Publisher[Any])
  extends ActorProcessorImpl(_settings) {
  import TwoStreamInputProcessor._

  val secondaryInputs: Inputs = new BatchingInputBuffer(settings.initialInputBufferSize, this) {
    override val subreceive: SubReceive = new SubReceive(waitingForUpstream)

    override def inputOnError(e: Throwable): Unit = TwoStreamInputProcessor.this.onError(e)

    override def waitingForUpstream: Receive = {
      case OtherStreamOnComplete                ⇒ onComplete()
      case OtherStreamOnSubscribe(subscription) ⇒ onSubscribe(subscription)
      case OtherStreamOnError(e)                ⇒ TwoStreamInputProcessor.this.onError(e)
    }

    override def upstreamRunning: Receive = {
      case OtherStreamOnNext(element) ⇒ enqueueInputElement(element)
      case OtherStreamOnComplete      ⇒ onComplete()
      case OtherStreamOnError(e)      ⇒ TwoStreamInputProcessor.this.onError(e)
    }
    override protected def completed: Actor.Receive = {
      case OtherStreamOnSubscribe(_) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    }
  }

  override def receive = secondaryInputs.subreceive orElse primaryInputs.subreceive orElse primaryOutputs.subreceive

  other.subscribe(new OtherActorSubscriber(self))

  override def shutdownHooks(): Unit = {
    secondaryInputs.cancel()
    super.shutdownHooks()
  }
}

/**
 * INTERNAL API
 */
private[akka] object MultiStreamInputProcessor {
  case class SubstreamKey(id: Int)

  class SubstreamSubscriber[T](val impl: ActorRef, key: SubstreamKey) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = impl ! SubstreamOnError(key, cause)
    override def onComplete(): Unit = impl ! SubstreamOnComplete(key)
    override def onNext(element: T): Unit = impl ! SubstreamOnNext(key, element)
    override def onSubscribe(subscription: Subscription): Unit = impl ! SubstreamStreamOnSubscribe(key, subscription)
  }

  case class SubstreamOnComplete(key: SubstreamKey)
  case class SubstreamOnNext(key: SubstreamKey, element: Any)
  case class SubstreamOnError(key: SubstreamKey, e: Throwable)
  case class SubstreamStreamOnSubscribe(key: SubstreamKey, subscription: Subscription)
}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamInputProcessor(_settings: MaterializerSettings) extends ActorProcessorImpl(_settings) {
  import MultiStreamInputProcessor._
  var nextId = 0

  private val substreamInputs = collection.mutable.Map.empty[SubstreamKey, SubstreamInputs]

  class SubstreamInputs(val key: SubstreamKey) extends BatchingInputBuffer(settings.initialInputBufferSize, pump = this) {
    // Not driven directly
    override val subreceive = new SubReceive(Actor.emptyBehavior)

    def substreamOnComplete(): Unit = onComplete()
    def substreamOnSubscribe(subscription: Subscription): Unit = onSubscribe(subscription)
    def substreamOnError(e: Throwable): Unit = onError(e)
    def substreamOnNext(elem: Any): Unit = enqueueInputElement(elem)

    override protected def inputOnError(e: Throwable): Unit = {
      super.inputOnError(e)
      invalidateSubstream(key, e)
    }
  }

  val substreamManagement: Receive = {
    case SubstreamStreamOnSubscribe(key, subscription) ⇒ substreamInputs(key).substreamOnSubscribe(subscription)
    case SubstreamOnNext(key, element)                 ⇒ substreamInputs(key).substreamOnNext(element)
    case SubstreamOnComplete(key) ⇒ {
      substreamInputs(key).substreamOnComplete()
      substreamInputs -= key
    }
    case SubstreamOnError(key, e) ⇒ substreamInputs(key).substreamOnError(e)

  }

  def createSubstreamInputs(p: Publisher[Any]): SubstreamInputs = {
    val key = SubstreamKey(nextId)
    val inputs = new SubstreamInputs(key)
    p.subscribe(new SubstreamSubscriber(self, key))
    substreamInputs(key) = inputs
    nextId += 1
    inputs
  }

  protected def invalidateSubstream(substream: SubstreamKey, e: Throwable): Unit = {
    substreamInputs(substream).cancel()
    substreamInputs -= substream
    pump()
  }

  override def fail(e: Throwable): Unit = {
    substreamInputs.values foreach (_.cancel())
    super.fail(e)
  }

  override def shutdownHooks(): Unit = {
    substreamInputs.values foreach (_.cancel())
    super.shutdownHooks()
  }

  override def receive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse substreamManagement
}