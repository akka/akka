/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import org.reactivestreams.api.Producer
import scala.concurrent.forkjoin.ThreadLocalRandom

/**
 * INTERNAL API
 */
private[akka] class MergeImpl(_settings: MaterializerSettings, _other: Producer[Any])
  extends TwoStreamInputProcessor(_settings, _other) {

  lazy val needsAnyInputAndDemand = (primaryInputs.NeedsInput || secondaryInputs.NeedsInput) && PrimaryOutputs.NeedsDemand

  override def initialTransferState = needsAnyInputAndDemand
  override def transfer(): TransferState = {
    // TODO: More flexible merging strategies are possible here. This takes a random element if we have elements
    // from both upstreams.
    val tieBreak = ThreadLocalRandom.current().nextBoolean()
    if (primaryInputs.inputsAvailable && (!secondaryInputs.inputsAvailable || tieBreak)) {
      PrimaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
    } else {
      PrimaryOutputs.enqueueOutputElement(secondaryInputs.dequeueInputElement())
    }
    needsAnyInputAndDemand
  }

}

/**
 * INTERNAL API
 */
private[akka] class ZipImpl(_settings: MaterializerSettings, _other: Producer[Any])
  extends TwoStreamInputProcessor(_settings, _other) {

  lazy val needsBothInputAndDemand = primaryInputs.NeedsInput && secondaryInputs.NeedsInput && PrimaryOutputs.NeedsDemand

  override def initialTransferState = needsBothInputAndDemand
  override protected def transfer(): TransferState = {
    PrimaryOutputs.enqueueOutputElement((primaryInputs.dequeueInputElement(), secondaryInputs.dequeueInputElement()))
    needsBothInputAndDemand
  }
}

/**
 * INTERNAL API
 */
private[akka] class ConcatImpl(_settings: MaterializerSettings, _other: Producer[Any])
  extends TwoStreamInputProcessor(_settings, _other) {

  lazy val needsPrimaryInputAndDemandWithComplete = primaryInputs.NeedsInputOrComplete && PrimaryOutputs.NeedsDemand
  lazy val needsSecondaryInputAndDemand = secondaryInputs.NeedsInput && PrimaryOutputs.NeedsDemand
  var processingPrimary = true

  override protected def initialTransferState: TransferState = needsPrimaryInputAndDemandWithComplete
  override protected def transfer(): TransferState = {
    if (processingPrimary) {
      if (primaryInputs.inputsDepleted) {
        processingPrimary = false
        needsSecondaryInputAndDemand
      } else {
        PrimaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
        needsPrimaryInputAndDemandWithComplete
      }
    } else {
      PrimaryOutputs.enqueueOutputElement(secondaryInputs.dequeueInputElement())
      needsSecondaryInputAndDemand
    }
  }

}
