/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.stream.MaterializerSettings
import akka.stream.impl.TransferPhase
import akka.stream.impl.MultiStreamOutputProcessor
import akka.stream.scaladsl2.Source

/**
 * INTERNAL API
 */
private[akka] class SplitWhenProcessorImpl(_settings: MaterializerSettings, val splitPredicate: Any ⇒ Boolean)
  extends MultiStreamOutputProcessor(_settings) {

  import MultiStreamOutputProcessor._

  var currentSubstream: SubstreamOutput = _

  val waitFirst = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    nextPhase(openSubstream(primaryInputs.dequeueInputElement()))
  }

  def openSubstream(elem: Any): TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    val substreamOutput = createSubstreamOutput()
    val substreamFlow = Source(substreamOutput) // substreamOutput is a Publisher
    primaryOutputs.enqueueOutputElement(substreamFlow)
    currentSubstream = substreamOutput
    nextPhase(serveSubstreamFirst(currentSubstream, elem))
  }

  // Serving the substream is split into two phases to minimize elements "held in hand"
  def serveSubstreamFirst(substream: SubstreamOutput, elem: Any) = TransferPhase(substream.NeedsDemand) { () ⇒
    substream.enqueueOutputElement(elem)
    nextPhase(serveSubstreamRest(substream))
  }

  // Note that this phase is allocated only once per _slice_ and not per element
  def serveSubstreamRest(substream: SubstreamOutput) = TransferPhase(primaryInputs.NeedsInput && substream.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    if (splitPredicate(elem)) {
      currentSubstream.complete()
      currentSubstream = null
      nextPhase(openSubstream(elem))
    } else substream.enqueueOutputElement(elem)
  }

  // Ignore elements for a cancelled substream until a new substream needs to be opened
  val ignoreUntilNewSubstream = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    if (splitPredicate(elem)) nextPhase(openSubstream(elem))
  }

  nextPhase(waitFirst)

  override def invalidateSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
    super.invalidateSubstreamOutput(substream)
  }

}
