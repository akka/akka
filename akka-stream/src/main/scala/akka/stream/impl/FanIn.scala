/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor._
import akka.stream.{ AbruptTerminationException, ActorMaterializerSettings, InPort, Shape }
import akka.stream.actor.{ ActorSubscriberMessage, ActorSubscriber }
import akka.stream.scaladsl.FlexiMerge.MergeLogic
import org.reactivestreams.{ Subscription, Subscriber }

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] object FanIn {

  final case class OnError(id: Int, cause: Throwable) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class OnComplete(id: Int) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class OnNext(id: Int, e: Any) extends DeadLetterSuppression with NoSerializationVerificationNeeded
  final case class OnSubscribe(id: Int, subscription: Subscription) extends DeadLetterSuppression with NoSerializationVerificationNeeded

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

  final val Marked = 1
  final val Pending = 2
  final val Depleted = 4
  final val Completed = 8
  final val Cancelled = 16

  type State = Byte

  abstract class InputBunch(inputCount: Int, bufferSize: Int, pump: Pump) {
    private var allCancelled = false

    private val inputs: Array[BatchingInputBuffer] = Array.tabulate(inputCount) { i ⇒
      new BatchingInputBuffer(bufferSize, pump) {
        override protected def onError(e: Throwable): Unit = InputBunch.this.onError(i, e)
      }
    }

    private val states = Array.ofDim[State](inputCount)
    private var markCount = 0
    private var markedPending = 0
    private var markedDepleted = 0

    private[this] final def has(s: State, f: Int): Boolean = (s & f) != 0
    private[this] final def set(input: Int, f: Int): Unit = {
      val s = states(input)
      states(input) = (s | f).toByte
    }
    private[this] final def unset(input: Int, f: Int): Unit = {
      val s = states(input)
      states(input) = (s & ~f).toByte
    }

    override def toString: String =
      s"""|InputBunch
          |  marked:    ${states.iterator.map(has(_, Marked)).mkString(", ")}
          |  pending:   ${states.iterator.map(has(_, Pending)).mkString(", ")}
          |  depleted:  ${states.iterator.map(has(_, Depleted)).mkString(", ")}
          |  completed: ${states.iterator.map(has(_, Completed)).mkString(", ")}
          |  cancelled: ${states.iterator.map(has(_, Cancelled)).mkString(", ")}
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

    def cancel(input: Int) = {
      val state = states(input)
      if (!has(state, Cancelled)) {
        inputs(input).cancel()
        set(input, Cancelled)
        unmarkInput(input)
      }
    }

    def onError(input: Int, e: Throwable): Unit

    def onDepleted(input: Int): Unit = ()

    def markInput(input: Int): Unit = {
      val state = states(input)
      if (!has(state, Marked)) {
        if (has(state, Depleted)) markedDepleted += 1
        if (has(state, Pending)) markedPending += 1
        set(input, Marked)
        markCount += 1
      }
    }

    def unmarkInput(input: Int): Unit = {
      val state = states(input)
      if (has(state, Marked)) {
        if (has(state, Depleted)) markedDepleted -= 1
        if (has(state, Pending)) markedPending -= 1
        unset(input, Marked)
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

    def isPending(input: Int): Boolean = has(states(input), Pending)

    def isDepleted(input: Int): Boolean = has(states(input), Depleted)

    def isCancelled(input: Int): Boolean = has(states(input), Cancelled)

    def idToDequeue(): Int = {
      var id = preferredId
      var state = states(id)
      while (!(has(state, Marked) && has(state, Pending))) {
        id += 1
        if (id == inputCount) id = 0
        assert(id != preferredId, "Tried to dequeue without waiting for any input")
        state = states(id)
      }
      id
    }

    def dequeue(id: Int): Any = {
      val state = states(id)
      require(!has(state, Depleted), s"Can't dequeue from depleted $id")
      require(has(state, Pending), s"No pending input at $id")
      _lastDequeuedId = id
      val input = inputs(id)
      val elem = input.dequeueInputElement()
      if (!input.inputsAvailable) {
        if (has(state, Marked)) markedPending -= 1
        unset(id, Pending)
      }
      if (input.inputsDepleted) {
        if (has(state, Marked)) markedDepleted += 1
        set(id, Depleted)
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
      override def isCompleted: Boolean = {
        val state = states(id)
        has(state, Depleted) || has(state, Cancelled)
      }
      override def isReady: Boolean = has(states(id), Pending)
    }

    def inputsOrCompleteAvailableFor(id: Int) = new TransferState {
      override def isCompleted: Boolean = false
      override def isReady: Boolean = {
        val state = states(id)
        has(state, Depleted) || has(state, Pending)
      }
    }

    // FIXME: Eliminate re-wraps
    def subreceive: SubReceive = new SubReceive({
      case OnSubscribe(id, subscription) ⇒
        inputs(id).subreceive(ActorSubscriber.OnSubscribe(subscription))
      case OnNext(id, elem) ⇒
        val state = states(id)
        if (has(state, Marked) && !has(state, Pending)) markedPending += 1
        set(id, Pending)
        inputs(id).subreceive(ActorSubscriberMessage.OnNext(elem))
      case OnComplete(id) ⇒
        val state = states(id)
        if (!has(state, Pending)) {
          if (has(state, Marked) && !has(state, Depleted)) markedDepleted += 1
          set(id, Depleted)
          onDepleted(id)
        }
        set(id, Completed)
        inputs(id).subreceive(ActorSubscriberMessage.OnComplete)
      case OnError(id, e) ⇒
        onError(id, e)
    })

  }

}

/**
 * INTERNAL API
 */
private[akka] abstract class FanIn(val settings: ActorMaterializerSettings, val inputCount: Int) extends Actor with ActorLogging with Pump {
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
  def props(settings: ActorMaterializerSettings, inputPorts: Int): Props =
    Props(new FairMerge(settings, inputPorts)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] final class FairMerge(_settings: ActorMaterializerSettings, _inputPorts: Int) extends FanIn(_settings, _inputPorts) {
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

  def props(settings: ActorMaterializerSettings, inputPorts: Int): Props =
    Props(new UnfairMerge(settings, inputPorts, DefaultPreferred)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] final class UnfairMerge(_settings: ActorMaterializerSettings,
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
  def props[T, S <: Shape](settings: ActorMaterializerSettings, ports: S, mergeLogic: MergeLogic[T]): Props =
    Props(new FlexiMergeImpl(settings, ports, mergeLogic)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] object Concat {
  def props(settings: ActorMaterializerSettings): Props = Props(new Concat(settings)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] final class Concat(_settings: ActorMaterializerSettings) extends FanIn(_settings, inputCount = 2) {
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
