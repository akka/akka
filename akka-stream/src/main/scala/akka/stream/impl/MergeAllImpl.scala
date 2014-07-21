/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import akka.stream.impl.MultiStreamInputProcessor.SubstreamKey
import org.reactivestreams.api.Producer

/**
 * INTERNAL API
 */
private[akka] class MergeAllImpl(_settings: MaterializerSettings, maxSimultaneousInputs: Int)
  extends MultiStreamInputProcessor(_settings) {
  var inputs = List[SubstreamInputs]()

  object InputReady extends TransferState {
    override def isReady = primaryInputs.inputsAvailable &&
      inputs.filterNot(_.inputsDepleted).size < maxSimultaneousInputs
    override def isCompleted = primaryInputs.inputsDepleted
  }
  object SubstreamsReady extends TransferState {
    override def isReady = inputs.exists(_.inputsAvailable)
    override def isCompleted = !inputs.exists(!_.inputsDepleted)
  }

  val running: TransferPhase = TransferPhase(
    primaryOutputs.NeedsDemand && (InputReady || SubstreamsReady)) { () ⇒
      while (InputReady.isExecutable) {
        fetchNewInputSubstream()
      }
      if (SubstreamsReady.isExecutable) {
        inputs.find(_.inputsAvailable) foreach { input ⇒
          primaryOutputs.enqueueOutputElement(input.dequeueInputElement())
        }
      }
      inputs = inputs.filterNot(_.inputsDepleted)
    }

  def fetchNewInputSubstream() = {
    val producer = primaryInputs.dequeueInputElement().asInstanceOf[Producer[Any]]
    inputs :+= createSubstreamInputs(producer)
  }

  override def invalidateSubstream(substream: SubstreamKey, e: Throwable): Unit = {
    fail(e)
  }

  nextPhase(running)
}
