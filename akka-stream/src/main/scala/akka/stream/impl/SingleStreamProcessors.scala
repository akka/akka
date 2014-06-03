/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.util.{ Failure, Success }
import akka.actor.Props
import akka.stream.MaterializerSettings
import akka.stream.Transformer
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] class TransformProcessorImpl(_settings: MaterializerSettings, transformer: Transformer[Any, Any]) extends ActorProcessorImpl(_settings) {
  var hasCleanupRun = false
  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]
  var errorEvent: Option[Throwable] = None

  override def preStart(): Unit = {
    super.preStart()
    nextPhase(running)
  }

  override def onError(e: Throwable): Unit = {
    try {
      transformer.onError(e)
      errorEvent = Some(e)
      pump()
    } catch { case NonFatal(ex) ⇒ fail(ex) }
  }

  object NeedsInputAndDemandOrCompletion extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    def isCompleted = false
  }

  private val runningPhase: TransferPhase = TransferPhase(NeedsInputAndDemandOrCompletion) { () ⇒
    if (primaryInputs.inputsDepleted) nextPhase(terminate)
    else {
      emits = transformer.onNext(primaryInputs.dequeueInputElement())
      if (transformer.isComplete) emitAndThen(terminate)
      else emitAndThen(running)
    }
  }

  def running: TransferPhase = runningPhase

  val terminate = TransferPhase(Always) { () ⇒
    emits = transformer.onTermination(errorEvent)
    emitAndThen(completedPhase)
  }

  // Save previous phase we should return to in a var to avoid allocation
  var phaseAfterFlush: TransferPhase = _

  // Enters flushing phase if there are emits pending
  def emitAndThen(andThen: TransferPhase): Unit =
    if (emits.nonEmpty) {
      phaseAfterFlush = andThen
      nextPhase(emitting)
    } else nextPhase(andThen)

  // Emits all pending elements, then returns to savedPhase
  val emitting = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(emits.head)
    emits = emits.tail
    if (emits.isEmpty) nextPhase(phaseAfterFlush)
  }

  override def toString: String = s"Transformer(emits=$emits, transformer=$transformer)"

  override def softShutdown(): Unit = {
    transformer.cleanup()
    hasCleanupRun = true // for postStop
    super.softShutdown()
  }

  override def postStop(): Unit = {
    try super.postStop() finally if (!hasCleanupRun) transformer.cleanup()
  }
}

/**
 * INTERNAL API
 */
private[akka] class SingleElementProcessorImpl(_settings: MaterializerSettings, f: Any ⇒ Any) extends ActorProcessorImpl(_settings) {

  object NeedsInputAndDemandOrCompletion extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    def isCompleted = false
  }

  val running: TransferPhase = TransferPhase(NeedsInputAndDemandOrCompletion) { () ⇒
    if (primaryInputs.inputsDepleted) nextPhase(completedPhase)
    else {
      val emit = f(primaryInputs.dequeueInputElement())
      if (emit != null) primaryOutputs.enqueueOutputElement(emit)
    }
  }

  nextPhase(running)
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

  val running: TransferPhase = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
  }

  nextPhase(running)
}
