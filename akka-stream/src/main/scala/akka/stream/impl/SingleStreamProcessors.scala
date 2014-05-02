/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.util.{ Failure, Success }
import akka.actor.Props
import akka.stream.MaterializerSettings
import akka.stream.scaladsl.RecoveryTransformer
import akka.stream.scaladsl.Transformer
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] class TransformProcessorImpl(_settings: MaterializerSettings, transformer: Transformer[Any, Any]) extends ActorProcessorImpl(_settings) {
  var isComplete = false
  var hasOnCompleteRun = false
  var hasCleanupRun = false
  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]

  object NeedsInputAndDemandOrCompletion extends TransferState {
    def isReady = (primaryInputs.inputsAvailable && primaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    def isCompleted = false
  }

  override def initialTransferState = NeedsInputAndDemandOrCompletion

  override def transfer(): TransferState = {
    val depleted = primaryInputs.inputsDepleted
    if (emits.isEmpty) {
      isComplete = transformer.isComplete
      if (depleted || isComplete) {
        emits = transformer.onComplete()
        hasOnCompleteRun = true
      } else {
        val e = primaryInputs.dequeueInputElement()
        emits = transformer.onNext(e)
      }
    } else {
      primaryOutputs.enqueueOutputElement(emits.head)
      emits = emits.tail
    }

    if (emits.nonEmpty) primaryOutputs.NeedsDemand
    else if (hasOnCompleteRun) Completed
    else NeedsInputAndDemandOrCompletion
  }

  override def toString: String = s"Transformer(isComplete=$isComplete, hasOnCompleteRun=$hasOnCompleteRun, emits=$emits)"

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
private[akka] class RecoverProcessorImpl(_settings: MaterializerSettings, recoveryTransformer: RecoveryTransformer[Any, Any])
  extends TransformProcessorImpl(_settings, recoveryTransformer) {

  override def primaryInputOnError(e: Throwable): Unit =
    try {
      emits = recoveryTransformer.onError(e)
      primaryInputs.complete()
      context.become(flushing)
      pump()
    } catch { case NonFatal(e) ⇒ fail(e) }
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
    primaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
    needsPrimaryInputAndDemand
  }

}
