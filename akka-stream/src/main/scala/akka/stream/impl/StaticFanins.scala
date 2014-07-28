/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.MaterializerSettings
import org.reactivestreams.Publisher
import scala.concurrent.forkjoin.ThreadLocalRandom

/**
 * INTERNAL API
 */
private[akka] class MergeImpl(_settings: MaterializerSettings, _other: Publisher[Any])
  extends TwoStreamInputProcessor(_settings, _other) {

  val runningPhase = TransferPhase(
    (primaryInputs.NeedsInput || secondaryInputs.NeedsInput) && primaryOutputs.NeedsDemand) { () ⇒
      def tieBreak = ThreadLocalRandom.current().nextBoolean()
      if (primaryInputs.inputsAvailable && (!secondaryInputs.inputsAvailable || tieBreak)) {
        primaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
      } else {
        primaryOutputs.enqueueOutputElement(secondaryInputs.dequeueInputElement())
      }
    }

  nextPhase(runningPhase)
}

/**
 * INTERNAL API
 */
private[akka] class ZipImpl(_settings: MaterializerSettings, _other: Publisher[Any])
  extends TwoStreamInputProcessor(_settings, _other) {

  val runningPhase = TransferPhase(primaryInputs.NeedsInput && secondaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement((primaryInputs.dequeueInputElement(), secondaryInputs.dequeueInputElement()))
  }

  nextPhase(runningPhase)

}

/**
 * INTERNAL API
 */
private[akka] class ConcatImpl(_settings: MaterializerSettings, _other: Publisher[Any])
  extends TwoStreamInputProcessor(_settings, _other) {

  val processingPrimary = TransferPhase(primaryInputs.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
    if (primaryInputs.inputsDepleted) nextPhase(processingSecondary)
    else primaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
  }

  val processingSecondary = TransferPhase(secondaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(secondaryInputs.dequeueInputElement())
  }

  nextPhase(processingPrimary)

}
