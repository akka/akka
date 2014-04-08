/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.util.{ Failure, Success }

import akka.actor.Props
import akka.stream.MaterializerSettings

/**
 * INTERNAL API
 */
private[akka] class TransformProcessorImpl(_settings: MaterializerSettings, op: Ast.Transform) extends ActorProcessorImpl(_settings) {
  var state = op.zero
  var isComplete = false
  var hasOnCompleteRun = false
  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]

  object NeedsInputAndDemandOrCompletion extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && PrimaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    def isCompleted = false
  }

  override def initialTransferState = NeedsInputAndDemandOrCompletion

  override def transfer(): TransferState = {
    val depleted = primaryInputs.inputsDepleted
    if (emits.isEmpty) {
      isComplete = op.isComplete(state)
      if (depleted || isComplete) {
        emits = op.onComplete(state)
        hasOnCompleteRun = true
      } else {
        val e = primaryInputs.dequeueInputElement()
        val (nextState, newEmits) = op.f(state, e)
        state = nextState
        emits = newEmits
      }
    } else {
      PrimaryOutputs.enqueueOutputElement(emits.head)
      emits = emits.tail
    }

    if (emits.nonEmpty) PrimaryOutputs.NeedsDemand
    else if (hasOnCompleteRun) Completed
    else NeedsInputAndDemandOrCompletion
  }

  override def toString: String = s"Transformer(state=$state, isComplete=$isComplete, hasOnCompleteRun=$hasOnCompleteRun, emits=$emits)"

  override def softShutdown(): Unit = {
    op.cleanup(state)
    state = this // marker for postStop that cleanup has been done
    super.softShutdown()
  }

  override def postStop(): Unit = {
    try super.postStop() finally if (state != this) op.cleanup(state)
  }
}

/**
 * INTERNAL API
 */
private[akka] class RecoverProcessorImpl(_settings: MaterializerSettings, _op: Ast.Recover) extends TransformProcessorImpl(_settings, _op.t) {

  val wrapInSuccess: Receive = {
    case OnNext(elem) ⇒
      primaryInputs.enqueueInputElement(Success(elem))
      pump()
  }

  override def running: Receive = wrapInSuccess orElse super.running

  override def failureReceived(e: Throwable): Unit = {
    primaryInputs.enqueueInputElement(Failure(e))
    primaryInputs.complete()
    flushAndComplete()
    pump()
  }
}

/**
 * INTERNAL API
 */
private[akka] object IdentityProcessorImpl {
  def props(settings: MaterializerSettings): Props = Props(new IdentityProcessorImpl(settings))
}

/**
 * INTERNAL API
 */
private[akka] class IdentityProcessorImpl(_settings: MaterializerSettings) extends ActorProcessorImpl(_settings) {

  override def initialTransferState = needsPrimaryInputAndDemand
  override protected def transfer(): TransferState = {
    PrimaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
    needsPrimaryInputAndDemand
  }

}
