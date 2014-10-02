/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import akka.stream.MaterializerSettings
import akka.stream.impl.TransferPhase
import akka.stream.scaladsl2.Source
import akka.stream.impl.MultiStreamOutputProcessor

/**
 * INTERNAL API
 */
private[akka] class GroupByProcessorImpl(settings: MaterializerSettings, val keyFor: Any ⇒ Any)
  extends MultiStreamOutputProcessor(settings) {

  import MultiStreamOutputProcessor._

  var keyToSubstreamOutput = collection.mutable.Map.empty[Any, SubstreamOutput]

  var pendingSubstreamOutput: SubstreamOutput = _

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

    keyToSubstreamOutput.get(key) match {
      case Some(substream) if substream.isOpen ⇒ nextPhase(dispatchToSubstream(elem, keyToSubstreamOutput(key)))
      case None if primaryOutputs.isOpen       ⇒ nextPhase(openSubstream(elem, key))
      case _                                   ⇒ // stay
    }
  }

  def openSubstream(elem: Any, key: Any): TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    if (primaryOutputs.isClosed) {
      // Just drop, we do not open any more substreams
      nextPhase(waitNext)
    } else {
      val substreamOutput = createSubstreamOutput()
      val substreamFlow = Source(substreamOutput) // substreamOutput is a Publisher
      primaryOutputs.enqueueOutputElement((key, substreamFlow))
      keyToSubstreamOutput(key) = substreamOutput
      nextPhase(dispatchToSubstream(elem, substreamOutput))
    }
  }

  def dispatchToSubstream(elem: Any, substream: SubstreamOutput): TransferPhase = {
    pendingSubstreamOutput = substream
    TransferPhase(substream.NeedsDemand) { () ⇒
      substream.enqueueOutputElement(elem)
      pendingSubstreamOutput = null
      nextPhase(waitNext)
    }
  }

  nextPhase(waitFirst)

  override def invalidateSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((pendingSubstreamOutput ne null) && substream == pendingSubstreamOutput.key) {
      pendingSubstreamOutput = null
      nextPhase(waitNext)
    }
    super.invalidateSubstreamOutput(substream)
  }

}
