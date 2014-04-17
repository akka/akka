/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.spi.Subscription
import akka.actor.{ Terminated, Props, ActorRef }
import akka.stream.MaterializerSettings
import akka.stream.impl._

/**
 * INTERNAL API
 */
private[akka] object GroupByProcessorImpl {

  sealed trait SubstreamElementState
  case object NoPending extends SubstreamElementState
  case class PendingElement(elem: Any, key: Any) extends SubstreamElementState
  case class PendingElementForNewStream(elem: Any, key: Any) extends SubstreamElementState
}

/**
 * INTERNAL API
 */
private[akka] class GroupByProcessorImpl(settings: MaterializerSettings, val keyFor: Any ⇒ Any) extends MultiStreamOutputProcessor(settings) {
  import GroupByProcessorImpl._

  var keyToSubstreamOutputs = collection.mutable.Map.empty[Any, SubstreamOutputs]
  var substreamPendingState: SubstreamElementState = NoPending

  override def initialTransferState = needsPrimaryInputAndDemand

  override def transfer(): TransferState = {
    substreamPendingState match {
      case PendingElementForNewStream(elem, key) ⇒
        if (primaryOutputs.isClosed) {
          substreamPendingState = NoPending
          // Just drop, we do not open any more substreams
        } else {
          val substreamOutput = newSubstream()
          primaryOutputs.enqueueOutputElement((key, substreamOutput.processor))
          keyToSubstreamOutputs(key) = substreamOutput
          substreamPendingState = PendingElement(elem, key)
        }

      case PendingElement(elem, key) ⇒
        if (keyToSubstreamOutputs(key).isOpen) keyToSubstreamOutputs(key).enqueueOutputElement(elem)
        substreamPendingState = NoPending

      case NoPending ⇒
        val elem = primaryInputs.dequeueInputElement()
        val key = keyFor(elem)

        substreamPendingState = keyToSubstreamOutputs.get(key) match {
          case Some(substream) if substream.isOpen ⇒ PendingElement(elem, key)
          case None if primaryOutputs.isOpen       ⇒ PendingElementForNewStream(elem, key)
          case _                                   ⇒ NoPending
        }
    }

    substreamPendingState match {
      case NoPending                        ⇒ primaryInputs.NeedsInput
      case PendingElement(_, key)           ⇒ keyToSubstreamOutputs(key).NeedsDemand
      case PendingElementForNewStream(_, _) ⇒ primaryOutputs.NeedsDemand
    }
  }

  override def invalidateSubstream(substream: ActorRef): Unit = {
    substreamPendingState match {
      case PendingElement(_, key) if keyToSubstreamOutputs(key).substream == substream ⇒
        setTransferState(primaryInputs.NeedsInput)
        substreamPendingState = NoPending
      case _ ⇒
    }
    super.invalidateSubstream(substream)
  }

}
