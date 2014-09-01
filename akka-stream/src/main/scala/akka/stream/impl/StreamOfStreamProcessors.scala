/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ Actor, ActorRef }
import akka.stream.MaterializerSettings
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import scala.collection.mutable

/**
 * INTERNAL API
 */
private[akka] object MultiStreamOutputProcessor {
  case class SubstreamKey(id: Long)
  case class SubstreamRequestMore(substream: SubstreamKey, demand: Int)
  case class SubstreamCancel(substream: SubstreamKey)
  case class SubstreamSubscribe(substream: SubstreamKey, subscriber: Subscriber[Any])

  class SubstreamSubscription(val parent: ActorRef, val substreamKey: SubstreamKey) extends Subscription {
    override def request(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else parent ! SubstreamRequestMore(substreamKey, elements)
    override def cancel(): Unit = parent ! SubstreamCancel(substreamKey)
    override def toString = "SubstreamSubscription" + System.identityHashCode(this)
  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class MultiStreamOutputProcessor(_settings: MaterializerSettings) extends ActorProcessorImpl(_settings) {
  import MultiStreamOutputProcessor._
  private var nextId = 0
  private val substreamOutputs = mutable.Map.empty[SubstreamKey, SubstreamOutputs]

  class SubstreamOutputs(val key: SubstreamKey) extends SimpleOutputs(self, this) with Publisher[Any] {

    sealed trait PublisherState
    sealed trait CompletedState extends PublisherState
    case object Open extends PublisherState
    final case class Attached(sub: Subscriber[Any]) extends PublisherState
    case object Completed extends CompletedState
    final case class Failed(e: Throwable) extends CompletedState

    private val subscription = new SubstreamSubscription(self, key)
    private val state = new AtomicReference[PublisherState](Open)

    override def subreceive: SubReceive =
      throw new UnsupportedOperationException("Substream outputs are managed in a dedicated receive block")

    def enqueueOutputDemand(demand: Int): Unit = {
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

    override def subscribe(s: Subscriber[Any]): Unit = {
      if (state.compareAndSet(Open, Attached(s))) self ! SubstreamSubscribe(key, s)
      else {
        state.get() match {
          case _: Attached       ⇒ s.onError(new IllegalStateException("Cannot subscribe two or more Subscribers to this Publisher"))
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

  protected def newSubstream(): SubstreamOutputs = {
    val id = SubstreamKey(nextId)
    nextId += 1
    val outputs = new SubstreamOutputs(id)
    substreamOutputs(outputs.key) = outputs
    outputs
  }

  protected def invalidateSubstream(substream: SubstreamKey): Unit = {
    substreamOutputs(substream).complete()
    substreamOutputs -= substream
    pump()
  }

  override def fail(e: Throwable): Unit = {
    substreamOutputs.values foreach (_.cancel(e))
    super.fail(e)
  }

  override def pumpFinished(): Unit = {
    substreamOutputs.values foreach (_.complete())
    super.pumpFinished()
  }

  val substreamManagement: Receive = {
    case SubstreamRequestMore(key, demand)   ⇒ substreamOutputs(key).enqueueOutputDemand(demand)
    case SubstreamCancel(key)                ⇒ invalidateSubstream(key)
    case SubstreamSubscribe(key, subscriber) ⇒ substreamOutputs(key).attachSubscriber(subscriber)
  }

  override def activeReceive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse substreamManagement
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
  import akka.stream.impl.MultiStreamInputProcessor._
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

  override def pumpFinished(): Unit = {
    substreamInputs.values foreach (_.cancel())
    super.pumpFinished()
  }

  override def activeReceive = primaryInputs.subreceive orElse primaryOutputs.subreceive orElse substreamManagement
}