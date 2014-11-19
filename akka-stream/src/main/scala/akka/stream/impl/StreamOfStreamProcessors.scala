/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorLogging
import akka.actor.Cancellable

import akka.actor.{ Actor, ActorRef }
import akka.stream.MaterializerSettings
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
private[akka] object MultiStreamOutputProcessor {
  case class SubstreamKey(id: Long)
  case class SubstreamRequestMore(substream: SubstreamKey, demand: Long)
  case class SubstreamCancel(substream: SubstreamKey)
  case class SubstreamSubscribe(substream: SubstreamKey, subscriber: Subscriber[Any])
  case class SubstreamSubscriptionTimeout(substream: SubstreamKey)

  class SubstreamSubscription(val parent: ActorRef, val substreamKey: SubstreamKey) extends Subscription {
    override def request(elements: Long): Unit =
      if (elements <= 0) throw new IllegalArgumentException(ReactiveStreamsCompliance.NumberOfElementsInRequestMustBePositiveMsg)
      else parent ! SubstreamRequestMore(substreamKey, elements)
    override def cancel(): Unit = parent ! SubstreamCancel(substreamKey)
    override def toString = "SubstreamSubscription" + System.identityHashCode(this)
  }

  object SubstreamOutput {
    sealed trait PublisherState
    sealed trait CompletedState extends PublisherState
    case object Open extends PublisherState
    final case class Attached(sub: Subscriber[Any]) extends PublisherState
    case object Completed extends CompletedState
    final case class Failed(e: Throwable) extends CompletedState
  }

  class SubstreamOutput(val key: SubstreamKey, actor: ActorRef, pump: Pump, subscriptionTimeout: Cancellable)
    extends SimpleOutputs(actor, pump) with Publisher[Any] {

    import SubstreamOutput._

    private val subscription = new SubstreamSubscription(actor, key)
    private val state = new AtomicReference[PublisherState](Open)

    override def subreceive: SubReceive =
      throw new UnsupportedOperationException("Substream outputs are managed in a dedicated receive block")

    def isAttached() = state.get().isInstanceOf[Attached]

    def enqueueOutputDemand(demand: Long): Unit = {
      downstreamDemand += demand
      pump.pump()
    }

    override def cancel(e: Throwable): Unit = {
      if (!downstreamCompleted) {
        closePublisher(Failed(e))
        downstreamCompleted = true
      }
    }

    override def complete(): Unit = {
      if (!downstreamCompleted) {
        closePublisher(Completed)
        downstreamCompleted = true
      }
    }

    private def closePublisher(withState: CompletedState): Unit = {
      subscriptionTimeout.cancel()
      state.getAndSet(withState) match {
        case Attached(sub)     ⇒ closeSubscriber(sub, withState)
        case _: CompletedState ⇒ throw new IllegalStateException("Attempted to double shutdown publisher")
        case Open              ⇒ // No action needed
      }
    }

    private def closeSubscriber(s: Subscriber[Any], withState: CompletedState): Unit = withState match {
      case Completed ⇒ s.onComplete()
      case Failed(e) ⇒ s.onError(e)
    }

    override def subscribe(s: Subscriber[_ >: Any]): Unit = {
      subscriptionTimeout.cancel()
      if (state.compareAndSet(Open, Attached(s))) actor ! SubstreamSubscribe(key, s)
      else {
        state.get() match {
          case _: Attached       ⇒ s.onError(new IllegalStateException("Substream publisher " + ReactiveStreamsCompliance.SupportsOnlyASingleSubscriber))
          case c: CompletedState ⇒ closeSubscriber(s, c)
          case Open              ⇒ throw new IllegalStateException("Publisher cannot become open after being used before")
        }
      }
    }

    def attachSubscriber(s: Subscriber[Any]): Unit =
      if (subscriber eq null) {
        subscriber = s
        subscriber.onSubscribe(subscription)
      } else subscriber.onError(new IllegalStateException("Cannot subscribe two or more Subscribers to this Publisher"))
  }
}

/**
 * INTERNAL API
 */
private[akka] trait MultiStreamOutputProcessorLike extends Pump with StreamSubscriptionTimeoutSupport {
  this: Actor with ActorLogging ⇒

  import MultiStreamOutputProcessor._
  import StreamSubscriptionTimeoutSupport._

  protected def nextId(): Long

  // stream keys will be removed from this map on cancellation/subscription-timeout, never assume a key is present
  private val substreamOutputs = mutable.Map.empty[SubstreamKey, SubstreamOutput]

  protected def createSubstreamOutput(): SubstreamOutput = {
    val id = SubstreamKey(nextId())
    val cancellable = scheduleSubscriptionTimeout(self, SubstreamSubscriptionTimeout(id))
    val output = new SubstreamOutput(id, self, this, cancellable)
    substreamOutputs(output.key) = output
    output
  }

  protected def invalidateSubstreamOutput(substream: SubstreamKey): Unit = {
    completeSubstreamOutput(substream)
    pump()
  }

  protected def completeSubstreamOutput(substream: SubstreamKey): Unit = {
    substreamOutputs.get(substream) match {
      case Some(sub) ⇒
        sub.complete()
        substreamOutputs -= substream
      case _ ⇒ // ignore, already completed...
    }
  }

  protected def failOutputs(e: Throwable): Unit = {
    substreamOutputs.values foreach (_.cancel(e))
  }

  protected def finishOutputs(): Unit = {
    substreamOutputs.values foreach (_.complete())
  }

  val outputSubstreamManagement: Receive = {
    case SubstreamRequestMore(key, demand) ⇒ substreamOutputs.get(key) match {
      case Some(sub) ⇒ sub.enqueueOutputDemand(demand)
      case _         ⇒ // ignore...
    }
    case SubstreamSubscribe(key, subscriber) ⇒ substreamOutputs.get(key) match {
      case Some(sub) ⇒ sub.attachSubscriber(subscriber)
      case _         ⇒ // ignore...
    }
    case SubstreamSubscriptionTimeout(key) ⇒ substreamOutputs.get(key) match {
      case Some(sub) if !sub.isAttached() ⇒ subscriptionTimedOut(sub)
      case _                              ⇒ // ignore...
    }
    case SubstreamCancel(key) ⇒ invalidateSubstreamOutput(key)
  }

  override protected def handleSubscriptionTimeout(target: Publisher[_], cause: Exception) = target match {
    case s: SubstreamOutput ⇒
      s.cancel(cause)
      s.attachSubscriber(CancelingSubscriber)
    case _ ⇒ // ignore
  }
}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamOutputProcessor(_settings: MaterializerSettings) extends ActorProcessorImpl(_settings) with MultiStreamOutputProcessorLike {
  private var _nextId = 0L
  protected def nextId(): Long = { _nextId += 1; _nextId }

  override val subscriptionTimeoutSettings = _settings.subscriptionTimeoutSettings

  override protected def fail(e: Throwable): Unit = {
    failOutputs(e)
    super.fail(e)
  }

  override def pumpFinished(): Unit = {
    finishOutputs()
    super.pumpFinished()
  }

  override def activeReceive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse outputSubstreamManagement
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
  import akka.stream.impl.TwoStreamInputProcessor._

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

  override def activeReceive: Receive =
    secondaryInputs.subreceive orElse primaryInputs.subreceive orElse primaryOutputs.subreceive

  other.subscribe(new OtherActorSubscriber(self))

  override def pumpFinished(): Unit = {
    secondaryInputs.cancel()
    super.pumpFinished()
  }

}

/**
 * INTERNAL API
 */
private[akka] object MultiStreamInputProcessor {
  case class SubstreamKey(id: Long)

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

  class SubstreamInput(val key: SubstreamKey, bufferSize: Int, processor: MultiStreamInputProcessorLike, pump: Pump) extends BatchingInputBuffer(bufferSize, pump) {
    // Not driven directly
    override val subreceive = new SubReceive(Actor.emptyBehavior)

    def substreamOnComplete(): Unit = onComplete()
    def substreamOnSubscribe(subscription: Subscription): Unit = onSubscribe(subscription)
    def substreamOnError(e: Throwable): Unit = onError(e)
    def substreamOnNext(elem: Any): Unit = enqueueInputElement(elem)

    override protected def inputOnError(e: Throwable): Unit = {
      super.inputOnError(e)
      processor.invalidateSubstreamInput(key, e)
    }
  }

}

/**
 * INTERNAL API
 */
private[akka] trait MultiStreamInputProcessorLike extends Pump { this: Actor ⇒

  import MultiStreamInputProcessor._

  protected def nextId(): Long
  protected def inputBufferSize: Int

  private val substreamInputs = collection.mutable.Map.empty[SubstreamKey, SubstreamInput]

  val inputSubstreamManagement: Receive = {
    case SubstreamStreamOnSubscribe(key, subscription) ⇒ substreamInputs(key).substreamOnSubscribe(subscription)
    case SubstreamOnNext(key, element)                 ⇒ substreamInputs(key).substreamOnNext(element)
    case SubstreamOnComplete(key) ⇒ {
      substreamInputs(key).substreamOnComplete()
      substreamInputs -= key
    }
    case SubstreamOnError(key, e) ⇒ substreamInputs(key).substreamOnError(e)

  }

  def createSubstreamInput(): SubstreamInput = {
    val key = SubstreamKey(nextId())
    val inputs = new SubstreamInput(key, inputBufferSize, this, this)
    substreamInputs(key) = inputs
    inputs
  }

  def createAndSubscribeSubstreamInput(p: Publisher[Any]): SubstreamInput = {
    val inputs = createSubstreamInput()
    p.subscribe(new SubstreamSubscriber(self, inputs.key))
    inputs
  }

  def invalidateSubstreamInput(substream: SubstreamKey, e: Throwable): Unit = {
    substreamInputs(substream).cancel()
    substreamInputs -= substream
    pump()
  }

  protected def failInputs(e: Throwable): Unit = {
    substreamInputs.values foreach (_.cancel())
  }

  protected def finishInputs(): Unit = {
    substreamInputs.values foreach (_.cancel())
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamInputProcessor(_settings: MaterializerSettings) extends ActorProcessorImpl(_settings) with MultiStreamInputProcessorLike {
  private var _nextId = 0L
  protected def nextId(): Long = { _nextId += 1; _nextId }

  override protected val inputBufferSize = _settings.initialInputBufferSize

  override protected def fail(e: Throwable) = {
    failInputs(e)
    super.fail(e)
  }

  override def pumpFinished() = {
    finishInputs()
    super.pumpFinished()
  }

  override def activeReceive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse inputSubstreamManagement
}