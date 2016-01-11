/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.stream.MaterializerSettings
import akka.stream.actor.{ ActorSubscriberMessage, ActorSubscriber }
import akka.stream.impl._
import org.reactivestreams.{ Subscription, Subscriber }
import akka.actor.Props

/**
 * INTERNAL API
 */
private[akka] object FanIn {

  case class OnError(id: Int, cause: Throwable)
  case class OnComplete(id: Int)
  case class OnNext(id: Int, e: Any)
  case class OnSubscribe(id: Int, subscription: Subscription)

  private[akka] final case class SubInput[T](impl: ActorRef, id: Int) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = impl ! OnError(id, cause)
    override def onComplete(): Unit = impl ! OnComplete(id)
    override def onNext(element: T): Unit = impl ! OnNext(id, element)
    override def onSubscribe(subscription: Subscription): Unit = impl ! OnSubscribe(id, subscription)
  }

  abstract class InputBunch(inputCount: Int, bufferSize: Int, pump: Pump) {
    private var cancelled = false

    private val inputs = Array.fill(inputCount)(new BatchingInputBuffer(bufferSize, pump) {
      override protected def onError(e: Throwable): Unit = InputBunch.this.onError(e)
    })

    private val marked = Array.ofDim[Boolean](inputCount)
    private var markCount = 0
    private val pending = Array.ofDim[Boolean](inputCount)
    private var markedPending = 0
    private val depleted = Array.ofDim[Boolean](inputCount)
    private val completed = Array.ofDim[Boolean](inputCount)
    private var markedDepleted = 0

    private var preferredId = 0

    def cancel(): Unit =
      if (!cancelled) {
        cancelled = true
        inputs foreach (_.cancel())
      }

    def onError(e: Throwable): Unit

    def markInput(input: Int): Unit = {
      if (!marked(input)) {
        if (depleted(input)) markedDepleted += 1
        if (pending(input)) markedPending += 1
        marked(input) = true
        markCount += 1
      }
    }

    def unmarkInput(input: Int): Unit = {
      if (marked(input)) {
        if (depleted(input)) markedDepleted -= 1
        if (pending(input)) markedPending -= 1
        marked(input) = false
        markCount -= 1
      }
    }

    def isDepleted(input: Int): Boolean = depleted(input)

    private def idToDequeue(): Int = {
      var id = preferredId
      while (!(marked(id) && pending(id))) {
        id += 1
        if (id == inputCount) id = 0
        assert(id != preferredId, "Tried to dequeue without waiting for any input")
      }
      id
    }

    def dequeue(id: Int): Any = {
      val input = inputs(id)
      val elem = input.dequeueInputElement()
      if (!input.inputsAvailable) {
        if (marked(id)) markedPending -= 1
        pending(id) = false
      }
      if (input.inputsDepleted) {
        depleted(id) = true
        if (marked(id)) markedDepleted += 1
      }
      elem
    }

    def dequeueAndYield(): Any = {
      val id = idToDequeue()
      preferredId = id + 1
      if (preferredId == inputCount) preferredId = 0
      dequeue(id)
    }
    def dequeueAndPrefer(preferred: Int): Any = {
      preferredId = preferred
      val id = idToDequeue()
      dequeue(id)
    }

    val AllOfMarkedInputs = new TransferState {
      override def isCompleted: Boolean = markedDepleted > 0
      override def isReady: Boolean = markedPending == markCount
    }

    val AnyOfMarkedInputs = new TransferState {
      override def isCompleted: Boolean = markedDepleted == markCount && markedPending == 0
      override def isReady: Boolean = markedPending > 0
    }

    def inputsAvailableFor(id: Int) = new TransferState {
      override def isCompleted: Boolean = depleted(id)
      override def isReady: Boolean = pending(id)
    }

    def inputsOrCompleteAvailableFor(id: Int) = new TransferState {
      override def isCompleted: Boolean = false
      override def isReady: Boolean = pending(id) || depleted(id)
    }

    // FIXME: Eliminate re-wraps
    def subreceive: SubReceive = new SubReceive({
      case OnSubscribe(id, subscription) ⇒
        inputs(id).subreceive(ActorSubscriber.OnSubscribe(subscription))
      case OnNext(id, elem) ⇒
        if (marked(id) && !pending(id)) markedPending += 1
        pending(id) = true
        inputs(id).subreceive(ActorSubscriberMessage.OnNext(elem))
      case OnComplete(id) ⇒
        if (!pending(id)) {
          if (marked(id) && !depleted(id)) markedDepleted += 1
          depleted(id) = true
        }
        completed(id) = true
        inputs(id).subreceive(ActorSubscriberMessage.OnComplete)
      case OnError(id, e) ⇒ onError(e)
    })

  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class FanIn(val settings: MaterializerSettings, val inputPorts: Int) extends Actor with ActorLogging with Pump {
  import FanIn._

  protected val primaryOutputs: Outputs = new SimpleOutputs(self, this)
  protected val inputBunch = new InputBunch(inputPorts, settings.maxInputBufferSize, this) {
    override def onError(e: Throwable): Unit = fail(e)
  }

  override def pumpFinished(): Unit = {
    inputBunch.cancel()
    primaryOutputs.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    inputBunch.cancel()
    primaryOutputs.cancel(e)
    context.stop(self)
  }

  override def postStop(): Unit = {
    inputBunch.cancel()
    primaryOutputs.cancel(new IllegalStateException("Processor actor terminated abruptly"))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted")
  }

  def receive = inputBunch.subreceive orElse primaryOutputs.subreceive

}

/**
 * INTERNAL API
 */
private[akka] object FairMerge {
  def props(settings: MaterializerSettings, inputPorts: Int): Props =
    Props(new FairMerge(settings, inputPorts))
}

/**
 * INTERNAL API
 */
private[akka] class FairMerge(_settings: MaterializerSettings, _inputPorts: Int) extends FanIn(_settings, _inputPorts) {
  (0 until inputPorts) foreach inputBunch.markInput

  nextPhase(TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeueAndYield()
    primaryOutputs.enqueueOutputElement(elem)
  })

}

/**
 * INTERNAL API
 */
private[akka] object UnfairMerge {
  val DefaultPreferred = 0

  def props(settings: MaterializerSettings, inputPorts: Int): Props =
    Props(new UnfairMerge(settings, inputPorts, DefaultPreferred))
}

/**
 * INTERNAL API
 */
private[akka] class UnfairMerge(_settings: MaterializerSettings, _inputPorts: Int, val preferred: Int) extends FanIn(_settings, _inputPorts) {
  (0 until inputPorts) foreach inputBunch.markInput

  nextPhase(TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeueAndPrefer(preferred)
    primaryOutputs.enqueueOutputElement(elem)
  })
}

/**
 * INTERNAL API
 */
private[akka] object Zip {
  def props(settings: MaterializerSettings): Props =
    Props(new Zip(settings))
}

/**
 * INTERNAL API
 */
private[akka] class Zip(_settings: MaterializerSettings) extends FanIn(_settings, inputPorts = 2) {
  inputBunch.markInput(0)
  inputBunch.markInput(1)

  nextPhase(TransferPhase(inputBunch.AllOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem0 = inputBunch.dequeue(0)
    val elem1 = inputBunch.dequeue(1)
    primaryOutputs.enqueueOutputElement((elem0, elem1))
  })
}

/**
 * INTERNAL API
 */
private[akka] object Concat {
  def props(settings: MaterializerSettings): Props =
    Props(new Concat(settings))
}

/**
 * INTERNAL API
 */
private[akka] class Concat(_settings: MaterializerSettings) extends FanIn(_settings, inputPorts = 2) {
  val First = 0
  val Second = 1

  def drainFirst = TransferPhase(inputBunch.inputsOrCompleteAvailableFor(First) && primaryOutputs.NeedsDemand) { () ⇒
    if (!inputBunch.isDepleted(First)) {
      val elem = inputBunch.dequeue(First)
      primaryOutputs.enqueueOutputElement(elem)
    } else {
      nextPhase(drainSecond)
    }
  }

  def drainSecond = TransferPhase(inputBunch.inputsAvailableFor(Second) && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeue(Second)
    primaryOutputs.enqueueOutputElement(elem)
  }

  nextPhase(drainFirst)
}