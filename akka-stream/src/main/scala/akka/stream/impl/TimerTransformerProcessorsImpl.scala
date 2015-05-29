/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.LinkedList
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.TimerTransformer
import scala.util.control.NonFatal
import akka.actor.{ Deploy, Props }

private[akka] object TimerTransformerProcessorsImpl {
  def props(settings: ActorFlowMaterializerSettings, transformer: TimerTransformer[Any, Any]): Props =
    Props(new TimerTransformerProcessorsImpl(settings, transformer)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] class TimerTransformerProcessorsImpl(
  _settings: ActorFlowMaterializerSettings,
  transformer: TimerTransformer[Any, Any])
  extends ActorProcessorImpl(_settings) with Emit {
  import TimerTransformer._

  var errorEvent: Option[Throwable] = None

  override def preStart(): Unit = {
    super.preStart()
    initialPhase(1, running)
    transformer.start(context)
  }

  override def postStop(): Unit =
    try {
      super.postStop()
      transformer.stop()
    } finally transformer.cleanup()

  override def onError(e: Throwable): Unit = {
    try {
      transformer.onError(e)
      errorEvent = Some(e)
      pump()
    } catch { case NonFatal(ex) ⇒ fail(ex) }
  }

  val schedulerInputs: Inputs = new DefaultInputTransferStates {
    val queue = new LinkedList[Any]

    override def dequeueInputElement(): Any = queue.removeFirst()

    override def subreceive: SubReceive = new SubReceive({
      case s: Scheduled ⇒
        try {
          transformer.onScheduled(s) foreach { elem ⇒
            queue.add(elem)
          }
          pump()
        } catch { case NonFatal(ex) ⇒ pumpFailed(ex) }
    })

    override def cancel(): Unit = ()
    override def isClosed: Boolean = false
    override def inputsDepleted: Boolean = false
    override def inputsAvailable: Boolean = !queue.isEmpty
  }

  override def activeReceive = super.activeReceive.orElse[Any, Unit](schedulerInputs.subreceive)

  object RunningCondition extends TransferState {
    def isReady = {
      ((primaryInputs.inputsAvailable || schedulerInputs.inputsAvailable || transformer.isComplete) &&
        primaryOutputs.demandAvailable) || primaryInputs.inputsDepleted
    }
    def isCompleted = false
  }

  private val running: TransferPhase = TransferPhase(RunningCondition) { () ⇒
    if (primaryInputs.inputsDepleted || (transformer.isComplete && !schedulerInputs.inputsAvailable)) {
      nextPhase(terminate)
    } else if (schedulerInputs.inputsAvailable) {
      emits = List(schedulerInputs.dequeueInputElement())
      emitAndThen(running)
    } else {
      emits = transformer.onNext(primaryInputs.dequeueInputElement())
      if (transformer.isComplete) emitAndThen(terminate)
      else emitAndThen(running)
    }
  }

  private val terminate = TransferPhase(Always) { () ⇒
    emits = transformer.onTermination(errorEvent)
    emitAndThen(completedPhase)
  }

  override def toString: String = s"Transformer(emits=$emits, transformer=$transformer)"

}
