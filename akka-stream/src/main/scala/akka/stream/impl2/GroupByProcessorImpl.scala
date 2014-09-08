/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.stream.MaterializerSettings
import akka.stream.impl.TransferPhase
import akka.stream.impl.MultiStreamOutputProcessor.SubstreamKey
import akka.stream.scaladsl2.FlowFrom
import akka.stream.impl.MultiStreamOutputProcessor

/**
 * INTERNAL API
 */
private[akka] class GroupByProcessorImpl(settings: MaterializerSettings, val keyFor: Any ⇒ Any) extends MultiStreamOutputProcessor(settings) {
  var keyToSubstreamOutputs = collection.mutable.Map.empty[Any, SubstreamOutputs]

  var pendingSubstreamOutputs: SubstreamOutputs = _

  // No substream is open yet. If downstream cancels now, we are complete
  val waitFirst = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    val key = keyFor(elem)
    nextPhase(openSubstream(elem, key))
  }

  // some substreams are open now. If downstream cancels, we still continue until the substreams are closed
  val waitNext = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    val key = keyFor(elem)

    keyToSubstreamOutputs.get(key) match {
      case Some(substream) if substream.isOpen ⇒ nextPhase(dispatchToSubstream(elem, keyToSubstreamOutputs(key)))
      case None if primaryOutputs.isOpen       ⇒ nextPhase(openSubstream(elem, key))
      case _                                   ⇒ // stay
    }
  }

  def openSubstream(elem: Any, key: Any): TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    if (primaryOutputs.isClosed) {
      // Just drop, we do not open any more substreams
      nextPhase(waitNext)
    } else {
      val substreamOutput = newSubstream()
      val substreamFlow = FlowFrom(substreamOutput) // substreamOutput is a Publisher
      primaryOutputs.enqueueOutputElement((key, substreamFlow))
      keyToSubstreamOutputs(key) = substreamOutput
      nextPhase(dispatchToSubstream(elem, substreamOutput))
    }
  }

  def dispatchToSubstream(elem: Any, substream: SubstreamOutputs): TransferPhase = {
    pendingSubstreamOutputs = substream
    TransferPhase(substream.NeedsDemand) { () ⇒
      substream.enqueueOutputElement(elem)
      pendingSubstreamOutputs = null
      nextPhase(waitNext)
    }
  }

  nextPhase(waitFirst)

  override def invalidateSubstream(substream: SubstreamKey): Unit = {
    if ((pendingSubstreamOutputs ne null) && substream == pendingSubstreamOutputs.key) {
      pendingSubstreamOutputs = null
      nextPhase(waitNext)
    }
    super.invalidateSubstream(substream)
  }

}
