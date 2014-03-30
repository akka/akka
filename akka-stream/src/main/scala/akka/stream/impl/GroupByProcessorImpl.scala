/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.spi.Subscription
import akka.actor.{ Terminated, Props, ActorRef }
import akka.stream.{ Stream, GeneratorSettings }
import akka.stream.impl._

/**
 * INTERNAL API
 */
private[akka] object GroupByProcessorImpl {

  trait SubstreamElementState
  case object NoPending extends SubstreamElementState
  case class PendingElement(elem: Any, key: Any) extends SubstreamElementState
  case class PendingElementForNewStream(elem: Any, key: Any) extends SubstreamElementState
}

/**
 * INTERNAL API
 */
private[akka] class GroupByProcessorImpl(settings: GeneratorSettings, val keyFor: Any ⇒ Any) extends MultiStreamOutputProcessor(settings) {
  import GroupByProcessorImpl._

  var keyToSubstreamOutputs = collection.mutable.Map.empty[Any, SubstreamOutputs]

  var substreamPendingState: SubstreamElementState = NoPending
  def substreamsFinished: Boolean = keyToSubstreamOutputs.isEmpty

  override def initialTransferState = needsPrimaryInputAndDemand

  override def transfer(): TransferState = substreamPendingState match {
    case PendingElementForNewStream(elem, key) ⇒
      if (PrimaryOutputs.isComplete) {
        substreamPendingState = NoPending
        // Just drop, we do not open any more substreams
        primaryInputs.NeedsInput
      } else {
        val substreamOutput = newSubstream()
        pushToDownstream((key, substreamOutput.processor))
        keyToSubstreamOutputs(key) = substreamOutput
        substreamPendingState = PendingElement(elem, key)
        substreamOutput.NeedsDemand
      }

    case PendingElement(elem, key) ⇒
      if (!keyToSubstreamOutputs(key).isComplete) keyToSubstreamOutputs(key).enqueueOutputElement(elem)
      substreamPendingState = NoPending
      primaryInputs.NeedsInput

    case NoPending ⇒
      val elem = primaryInputs.dequeueInputElement()
      val key = keyFor(elem)
      if (keyToSubstreamOutputs.contains(key)) {
        substreamPendingState = PendingElement(elem, key)
        keyToSubstreamOutputs(key).NeedsDemand
      } else if (!PrimaryOutputs.isComplete) {
        substreamPendingState = PendingElementForNewStream(elem, key)
        PrimaryOutputs.NeedsDemand
      } else primaryInputs.NeedsInput

  }

  override def invalidateSubstream(substream: ActorRef): Unit = {
    substreamPendingState match {
      case PendingElement(_, key) if keyToSubstreamOutputs(key).substream == substream ⇒
        transferState = primaryInputs.NeedsInput
        substreamPendingState = NoPending
      case PendingElementForNewStream(_, key) if keyToSubstreamOutputs(key).substream == substream ⇒
        transferState = primaryInputs.NeedsInput
        substreamPendingState = NoPending
      case _ ⇒
    }
    super.invalidateSubstream(substream)
  }

}
