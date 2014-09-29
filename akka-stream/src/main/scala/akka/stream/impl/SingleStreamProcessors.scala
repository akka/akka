/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.Props
import akka.stream.{ MaterializerSettings, TransformerLike }

import scala.collection.immutable
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] class TransformProcessorImpl(_settings: MaterializerSettings, transformer: TransformerLike[Any, Any])
  extends ActorProcessorImpl(_settings) with Emit {

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
    def isCompleted = primaryOutputs.isClosed
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

  override def toString: String = s"Transformer(emits=$emits, transformer=$transformer)"

  override def postStop(): Unit = try super.postStop() finally transformer.cleanup()
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
