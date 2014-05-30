/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import akka.stream.MaterializerSettings
import scala.util.control.NonFatal
import akka.stream.Transformer2

/**
 * INTERNAL API
 */
private[akka] class Transform2ProcessorImpl(_settings: MaterializerSettings, transformer: Transformer2[Any, Any]) extends ActorProcessorImpl(_settings) {
  var hasCleanupRun = false
  // TODO performance improvement: mutable buffer?
  var emits: Iterator[Any] = Iterator.empty
  var errorEvent: Option[Throwable] = None

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

  val running: TransferPhase = TransferPhase(NeedsInputAndDemandOrCompletion) { () ⇒
    if (primaryInputs.inputsDepleted) nextPhase(terminate)
    else {
      emits = transformer.onNext(primaryInputs.dequeueInputElement())
      if (transformer.isComplete) emitAndThen(terminate)
      else emitAndThen(running)
    }
  }

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
    primaryOutputs.enqueueOutputElement(emits.next())
    if (emits.isEmpty) nextPhase(phaseAfterFlush)
  }

  nextPhase(running)

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