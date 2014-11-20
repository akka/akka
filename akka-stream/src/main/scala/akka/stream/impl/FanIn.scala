/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.actor.Props
import akka.stream.MaterializerSettings
import akka.stream.actor.{ ActorSubscriberMessage, ActorSubscriber }
import org.reactivestreams.{ Subscription, Subscriber }

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
    private var allCancelled = false

    private val inputs: Array[BatchingInputBuffer] = Array.tabulate(inputCount) { i ⇒
      new BatchingInputBuffer(bufferSize, pump) {
        override protected def onError(e: Throwable): Unit = InputBunch.this.onError(i, e)
      }
    }

    private val marked = Array.ofDim[Boolean](inputCount)
    private var markCount = 0
    private val pending = Array.ofDim[Boolean](inputCount)
    private var markedPending = 0
    private val depleted = Array.ofDim[Boolean](inputCount)
    private val completed = Array.ofDim[Boolean](inputCount)
    private var markedDepleted = 0
    private val cancelled = Array.ofDim[Boolean](inputCount)

    private var preferredId = 0

    def cancel(): Unit =
      if (!allCancelled) {
        allCancelled = true
        var i = 0
        while (i < inputs.length) {
          cancel(i)
          i += 1
        }
      }

    def cancel(input: Int) =
      if (!cancelled(input)) {
        inputs(input).cancel()
        cancelled(input) = true
        unmarkInput(input)
      }

    def onError(input: Int, e: Throwable): Unit

    def onDepleted(input: Int): Unit = ()

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

    def markAllInputs(): Unit = {
      var i = 0
      while (i < inputCount) {
        markInput(i)
        i += 1
      }
    }

    def unmarkAllInputs(): Unit = {
      var i = 0
      while (i < inputCount) {
        unmarkInput(i)
        i += 1
      }
    }

    def isPending(input: Int): Boolean = pending(input)

    def isDepleted(input: Int): Boolean = depleted(input)

    def isCancelled(input: Int): Boolean = cancelled(input)

    def idToDequeue(): Int = {
      var id = preferredId
      while (!(marked(id) && pending(id))) {
        id += 1
        if (id == inputCount) id = 0
        assert(id != preferredId, "Tried to dequeue without waiting for any input")
      }
      id
    }

    def dequeue(id: Int): Any = {
      require(!isDepleted(id), s"Can't dequeue from depleted $id")
      require(isPending(id), s"No pending input at $id")
      val input = inputs(id)
      val elem = input.dequeueInputElement()
      if (!input.inputsAvailable) {
        if (marked(id)) markedPending -= 1
        pending(id) = false
      }
      if (input.inputsDepleted) {
        if (marked(id)) markedDepleted += 1
        depleted(id) = true
      }
      elem
    }

    def dequeueAndYield(): Any =
      dequeueAndYield(idToDequeue())

    def dequeueAndYield(id: Int): Any = {
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
      override def isCompleted: Boolean = (markedDepleted == markCount && markedPending == 0)
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
          onDepleted(id)
        }
        completed(id) = true
        inputs(id).subreceive(ActorSubscriberMessage.OnComplete)
      case OnError(id, e) ⇒
        onError(id, e)
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
    override def onError(input: Int, e: Throwable): Unit = fail(e)
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
private[akka] final class FairMerge(_settings: MaterializerSettings, _inputPorts: Int) extends FanIn(_settings, _inputPorts) {
  inputBunch.markAllInputs()

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
private[akka] final class UnfairMerge(_settings: MaterializerSettings,
                                      _inputPorts: Int,
                                      val preferred: Int) extends FanIn(_settings, _inputPorts) {
  inputBunch.markAllInputs()

  nextPhase(TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeueAndPrefer(preferred)
    primaryOutputs.enqueueOutputElement(elem)
  })
}

/**
 * INTERNAL API
 */
private[akka] object ZipWith {
  def props(settings: MaterializerSettings, f: (Any, Any) ⇒ Any): Props = Props(new ZipWith(settings, f))
}

/**
 * INTERNAL API
 */
private[akka] final class ZipWith(_settings: MaterializerSettings, f: (Any, Any) ⇒ Any) extends FanIn(_settings, inputPorts = 2) {
  inputBunch.markAllInputs()

  nextPhase(TransferPhase(inputBunch.AllOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem0 = inputBunch.dequeue(0)
    val elem1 = inputBunch.dequeue(1)
    primaryOutputs.enqueueOutputElement(f(elem0, elem1))
  })
}

/**
 * INTERNAL API
 */
private[akka] object Concat {
  def props(settings: MaterializerSettings): Props = Props(new Concat(settings))
}

/**
 * INTERNAL API
 */
private[akka] final class Concat(_settings: MaterializerSettings) extends FanIn(_settings, inputPorts = 2) {
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

