/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ ActorRef, ActorLogging, Actor }
import akka.actor.Props
import akka.stream.{ AbruptTerminationException, ActorFlowMaterializerSettings, InPort, Shape }
import akka.stream.actor.{ ActorSubscriberMessage, ActorSubscriber }
import akka.stream.scaladsl.FlexiMerge.MergeLogic
import org.reactivestreams.{ Subscription, Subscriber }
import akka.actor.DeadLetterSuppression

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] object FanIn {

  final case class OnError(id: Int, cause: Throwable) extends DeadLetterSuppression
  final case class OnComplete(id: Int) extends DeadLetterSuppression
  final case class OnNext(id: Int, e: Any) extends DeadLetterSuppression
  final case class OnSubscribe(id: Int, subscription: Subscription) extends DeadLetterSuppression

  private[akka] final case class SubInput[T](impl: ActorRef, id: Int) extends Subscriber[T] {
    override def onError(cause: Throwable): Unit = {
      ReactiveStreamsCompliance.requireNonNullException(cause)
      impl ! OnError(id, cause)
    }
    override def onComplete(): Unit = impl ! OnComplete(id)
    override def onNext(element: T): Unit = {
      ReactiveStreamsCompliance.requireNonNullElement(element)
      impl ! OnNext(id, element)
    }
    override def onSubscribe(subscription: Subscription): Unit = {
      ReactiveStreamsCompliance.requireNonNullSubscription(subscription)
      impl ! OnSubscribe(id, subscription)
    }
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

    override def toString: String =
      s"""|InputBunch
          |  marked:    ${marked.mkString(", ")}
          |  pending:   ${pending.mkString(", ")}
          |  depleted:  ${depleted.mkString(", ")}
          |  completed: ${completed.mkString(", ")}
          |  cancelled: ${cancelled.mkString(", ")}
          |    mark=$markCount pend=$markedPending depl=$markedDepleted pref=$preferredId""".stripMargin

    private var preferredId = 0
    private var _lastDequeuedId = 0
    def lastDequeuedId = _lastDequeuedId

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
      _lastDequeuedId = id
      val input = inputs(id)
      val elem = input.dequeueInputElement()
      if (!input.inputsAvailable) {
        if (marked(id)) markedPending -= 1
        pending(id) = false
      }
      if (input.inputsDepleted) {
        if (marked(id)) markedDepleted += 1
        depleted(id) = true
        onDepleted(id)
      }
      elem
    }

    def dequeueAndYield(): Any =
      dequeueAndYield(idToDequeue())

    def dequeueAndYield(id: Int): Any = {
      preferredId = id + 1
      if (preferredId == inputCount) preferredId = 0
      dequeue(id)
    }

    def dequeuePrefering(preferred: Int): Any = {
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
      override def isCompleted: Boolean = depleted(id) || cancelled(id)
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
private[akka] abstract class FanIn(val settings: ActorFlowMaterializerSettings, val inputCount: Int) extends Actor with ActorLogging with Pump {
  import FanIn._

  protected val primaryOutputs: Outputs = new SimpleOutputs(self, this)
  protected val inputBunch = new InputBunch(inputCount, settings.maxInputBufferSize, this) {
    override def onError(input: Int, e: Throwable): Unit = fail(e)
  }

  override def pumpFinished(): Unit = {
    inputBunch.cancel()
    primaryOutputs.complete()
    context.stop(self)
  }

  override def pumpFailed(e: Throwable): Unit = fail(e)

  protected def fail(e: Throwable): Unit = {
    if (settings.debugLogging)
      log.debug("fail due to: {}", e.getMessage)
    nextPhase(completedPhase)
    primaryOutputs.error(e)
    pump()
  }

  override def postStop(): Unit = {
    inputBunch.cancel()
    primaryOutputs.error(AbruptTerminationException(self))
  }

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    throw new IllegalStateException("This actor cannot be restarted")
  }

  def receive = inputBunch.subreceive.orElse[Any, Unit](primaryOutputs.subreceive)

}

/**
 * INTERNAL API
 */
private[akka] object FairMerge {
  def props(settings: ActorFlowMaterializerSettings, inputPorts: Int): Props =
    Props(new FairMerge(settings, inputPorts))
}

/**
 * INTERNAL API
 */
private[akka] final class FairMerge(_settings: ActorFlowMaterializerSettings, _inputPorts: Int) extends FanIn(_settings, _inputPorts) {
  inputBunch.markAllInputs()

  initialPhase(inputCount, TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeueAndYield()
    primaryOutputs.enqueueOutputElement(elem)
  })

}

/**
 * INTERNAL API
 */
private[akka] object UnfairMerge {
  val DefaultPreferred = 0

  def props(settings: ActorFlowMaterializerSettings, inputPorts: Int): Props =
    Props(new UnfairMerge(settings, inputPorts, DefaultPreferred))
}

/**
 * INTERNAL API
 */
private[akka] final class UnfairMerge(_settings: ActorFlowMaterializerSettings,
                                      _inputPorts: Int,
                                      val preferred: Int) extends FanIn(_settings, _inputPorts) {
  inputBunch.markAllInputs()

  initialPhase(inputCount, TransferPhase(inputBunch.AnyOfMarkedInputs && primaryOutputs.NeedsDemand) { () ⇒
    val elem = inputBunch.dequeuePrefering(preferred)
    primaryOutputs.enqueueOutputElement(elem)
  })
}

/**
 * INTERNAL API
 */
private[akka] object FlexiMerge {
  def props[T, S <: Shape](settings: ActorFlowMaterializerSettings, ports: S, mergeLogic: MergeLogic[T]): Props =
    Props(new FlexiMergeImpl(settings, ports, mergeLogic))
}

/**
 * INTERNAL API
 */
private[akka] object Concat {
  def props(settings: ActorFlowMaterializerSettings): Props = Props(new Concat(settings))
}

/**
 * INTERNAL API
 */
private[akka] final class Concat(_settings: ActorFlowMaterializerSettings) extends FanIn(_settings, inputCount = 2) {
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

  initialPhase(inputCount, drainFirst)
}
