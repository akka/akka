/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference
import akka.actor._
import akka.stream.ActorMaterializerSettings
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.collection.mutable

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

    def isAttached = state.get().isInstanceOf[Attached]

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
      case Some(sub) if !sub.isAttached ⇒ subscriptionTimedOut(sub)
      case _                            ⇒ // ignore...
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

