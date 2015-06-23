/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import akka.actor._
import akka.stream.ActorMaterializerSettings
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
private[akka] object MultiStreamOutputProcessor {
  final case class SubstreamKey(id: Long)
  final case class SubstreamRequestMore(substream: SubstreamKey, demand: Long) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class SubstreamCancel(substream: SubstreamKey) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class SubstreamSubscribe(substream: SubstreamKey, subscriber: Subscriber[Any]) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class SubstreamSubscriptionTimeout(substream: SubstreamKey) extends DeadLetterSuppression with NoSerializationVerificationNeeded

  class SubstreamSubscription(val parent: ActorRef, val substreamKey: SubstreamKey) extends Subscription {
    override def request(elements: Long): Unit = parent ! SubstreamRequestMore(substreamKey, elements)
    override def cancel(): Unit = parent ! SubstreamCancel(substreamKey)
    override def toString = "SubstreamSubscription" + System.identityHashCode(this)
  }

  object SubstreamOutput {
    sealed trait PublisherState
    sealed trait CompletedState extends PublisherState
    case object Open extends PublisherState
    final case class Attached(sub: Subscriber[Any]) extends PublisherState
    case object Completed extends CompletedState
    case object Cancelled extends CompletedState
    final case class Failed(e: Throwable) extends CompletedState
  }

  class SubstreamOutput(val key: SubstreamKey, actor: ActorRef, pump: Pump, subscriptionTimeout: Cancellable)
    extends SimpleOutputs(actor, pump) with Publisher[Any] {
    import ReactiveStreamsCompliance._

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

    override def error(e: Throwable): Unit = {
      if (!downstreamCompleted) {
        closePublisher(Failed(e))
        downstreamCompleted = true
      }
    }

    override def cancel(): Unit = {
      if (!downstreamCompleted) {
        closePublisher(Cancelled)
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
        case _: CompletedState ⇒ throw new IllegalStateException("Attempted to double shutdown publisher")
        case Attached(sub) ⇒
          if (subscriber eq null) tryOnSubscribe(sub, CancelledSubscription)
          closeSubscriber(sub, withState)
        case Open ⇒ // No action needed
      }
    }

    private def closeSubscriber(s: Subscriber[Any], withState: CompletedState): Unit = withState match {
      case Completed                ⇒ tryOnComplete(s)
      case Cancelled                ⇒ // nothing to do
      case Failed(e: SpecViolation) ⇒ // nothing to do
      case Failed(e)                ⇒ tryOnError(s, e)
    }

    override def subscribe(s: Subscriber[_ >: Any]): Unit = {
      requireNonNullSubscriber(s)
      subscriptionTimeout.cancel()
      if (state.compareAndSet(Open, Attached(s))) actor ! SubstreamSubscribe(key, s)
      else {
        state.get() match {
          case _: Attached | Cancelled ⇒
            rejectAdditionalSubscriber(s, "Substream publisher")
          case c: CompletedState ⇒
            tryOnSubscribe(s, CancelledSubscription)
            closeSubscriber(s, c)
          case Open ⇒
            throw new IllegalStateException("Publisher cannot become open after being used before")
        }
      }
    }

    def attachSubscriber(s: Subscriber[Any]): Unit =
      if (subscriber eq null) {
        subscriber = s
        tryOnSubscribe(subscriber, subscription)
      } else
        rejectAdditionalSubscriber(s, "Substream publisher")
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
    cancelSubstreamOutput(substream)
    pump()
  }

  protected def cancelSubstreamOutput(substream: SubstreamKey): Unit = {
    substreamOutputs.get(substream) match {
      case Some(sub) ⇒
        sub.cancel()
        substreamOutputs -= substream
      case _ ⇒ // ignore, already completed...
    }
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
    substreamOutputs.values foreach (_.error(e))
  }

  protected def finishOutputs(): Unit = {
    substreamOutputs.values foreach (_.complete())
  }

  val outputSubstreamManagement: Receive = {
    case SubstreamRequestMore(key, demand) ⇒
      substreamOutputs.get(key) match {
        case Some(sub) ⇒
          if (demand < 1) // According to Reactive Streams Spec 3.9, with non-positive demand must yield onError
            sub.error(ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException)
          else
            sub.enqueueOutputDemand(demand)
        case _ ⇒ // ignore...
      }
    case SubstreamSubscribe(key, subscriber) ⇒ substreamOutputs.get(key) match {
      case Some(sub) ⇒ sub.attachSubscriber(subscriber)
      case _         ⇒ // ignore...
    }
    case SubstreamSubscriptionTimeout(key) ⇒ substreamOutputs.get(key) match {
      case Some(sub) if !sub.isAttached() ⇒ subscriptionTimedOut(sub)
      case _                              ⇒ // ignore...
    }
    case SubstreamCancel(key) ⇒
      invalidateSubstreamOutput(key)
  }

  override protected def handleSubscriptionTimeout(target: Publisher[_], cause: Exception) = target match {
    case s: SubstreamOutput ⇒
      s.error(cause)
      s.attachSubscriber(CancelingSubscriber)
    case _ ⇒ // ignore
  }
}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamOutputProcessor(_settings: ActorMaterializerSettings) extends ActorProcessorImpl(_settings) with MultiStreamOutputProcessorLike {
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

  override def activeReceive: Receive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse outputSubstreamManagement
}

/**
 * INTERNAL API
 */
private[akka] object TwoStreamInputProcessor {
  class OtherActorSubscriber[T](val impl: ActorRef) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(cause)
      impl ! OtherStreamOnError(cause)
    }
    override def onComplete(): Unit = impl ! OtherStreamOnComplete
    override def onNext(element: T): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(element)
      impl ! OtherStreamOnNext(element)
    }
    override def onSubscribe(subscription: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
      impl ! OtherStreamOnSubscribe(subscription)
    }
  }

  case object OtherStreamOnComplete extends DeadLetterSuppression
  final case class OtherStreamOnNext(element: Any) extends DeadLetterSuppression
  final case class OtherStreamOnSubscribe(subscription: Subscription) extends DeadLetterSuppression
  final case class OtherStreamOnError(ex: Throwable) extends DeadLetterSuppression
}

/**
 * INTERNAL API
 */
private[akka] abstract class TwoStreamInputProcessor(_settings: ActorMaterializerSettings, val other: Publisher[Any])
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
      case OtherStreamOnSubscribe(_) ⇒ throw ActorPublisher.NormalShutdownReason
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

  class SubstreamSubscriber[T](val impl: ActorRef, key: SubstreamKey) extends AtomicReference[Subscription] with Subscriber[T] {
    override def onError(cause: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(cause)
      impl ! SubstreamOnError(key, cause)
    }
    override def onComplete(): Unit = impl ! SubstreamOnComplete(key)
    override def onNext(element: T): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(element)
      impl ! SubstreamOnNext(key, element)
    }
    override def onSubscribe(subscription: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
      if (compareAndSet(null, subscription)) impl ! SubstreamStreamOnSubscribe(key, subscription)
      else subscription.cancel()
    }
  }

  case class SubstreamOnComplete(key: SubstreamKey) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  case class SubstreamOnNext(key: SubstreamKey, element: Any) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  case class SubstreamOnError(key: SubstreamKey, e: Throwable) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  case class SubstreamStreamOnSubscribe(key: SubstreamKey, subscription: Subscription) extends DeadLetterSuppression with NoSerializationVerificationNeeded

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
  private val waitingForOnSubscribe = collection.mutable.Map.empty[SubstreamKey, SubstreamSubscriber[Any]]

  val inputSubstreamManagement: Receive = {
    case SubstreamStreamOnSubscribe(key, subscription) ⇒
      substreamInputs(key).substreamOnSubscribe(subscription)
      waitingForOnSubscribe -= key
    case SubstreamOnNext(key, element) ⇒
      substreamInputs(key).substreamOnNext(element)
    case SubstreamOnComplete(key) ⇒
      substreamInputs(key).substreamOnComplete()
      substreamInputs -= key
    case SubstreamOnError(key, e) ⇒
      substreamInputs(key).substreamOnError(e)
  }

  def createSubstreamInput(): SubstreamInput = {
    val key = SubstreamKey(nextId())
    val inputs = new SubstreamInput(key, inputBufferSize, this, this)
    substreamInputs(key) = inputs
    inputs
  }

  def createAndSubscribeSubstreamInput(p: Publisher[Any]): SubstreamInput = {
    val inputs = createSubstreamInput()
    val sub = new SubstreamSubscriber[Any](self, inputs.key)
    waitingForOnSubscribe(inputs.key) = sub
    p.subscribe(sub)
    inputs
  }

  def invalidateSubstreamInput(substream: SubstreamKey, e: Throwable): Unit = {
    substreamInputs(substream).cancel()
    substreamInputs -= substream
    pump()
  }

  protected def failInputs(e: Throwable): Unit = {
    cancelWaitingForOnSubscribe()
    substreamInputs.values foreach (_.cancel())
  }

  protected def finishInputs(): Unit = {
    cancelWaitingForOnSubscribe()
    substreamInputs.values foreach (_.cancel())
  }

  private def cancelWaitingForOnSubscribe(): Unit =
    waitingForOnSubscribe.valuesIterator.foreach { sub ⇒
      sub.getAndSet(CancelledSubscription) match {
        case null ⇒ // we were first
        case subscription ⇒
          // SubstreamOnSubscribe is still in flight and will not arrive
          subscription.cancel()
      }
    }

}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamInputProcessor(_settings: ActorMaterializerSettings) extends ActorProcessorImpl(_settings) with MultiStreamInputProcessorLike {
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
