/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Props, ActorRef }
import org.reactivestreams.spi.Subscription
import akka.stream.impl._
import akka.stream.MaterializerSettings
import akka.actor.Terminated

/**
 * INTERNAL API
 */
private[akka] object SplitWhenProcessorImpl {

  sealed trait SubstreamElementState
  case object NoPending extends SubstreamElementState
  case class PendingElement(elem: Any) extends SubstreamElementState
  case class PendingElementForNewStream(elem: Any) extends SubstreamElementState
}

/**
 * INTERNAL API
 */
private[akka] class SplitWhenProcessorImpl(_settings: MaterializerSettings, val splitPredicate: Any ⇒ Boolean)
  extends MultiStreamOutputProcessor(_settings) {
  import SplitWhenProcessorImpl._

  var pendingElement: SubstreamElementState = NoPending
  var started = false
  var currentSubstream: SubstreamOutputs = _

  override def initialTransferState = needsPrimaryInputAndDemand

  override def transfer(): TransferState = {
    pendingElement match {
      case NoPending ⇒
        val elem = primaryInputs.dequeueInputElement()
        if (!started) {
          pendingElement = PendingElementForNewStream(elem)
          started = true
        } else if (splitPredicate(elem)) {
          pendingElement = PendingElementForNewStream(elem)
          currentSubstream.complete()
        } else if (currentSubstream.isOpen) {
          pendingElement = PendingElement(elem)
        } else primaryInputs.NeedsInput
      case PendingElement(elem) ⇒
        currentSubstream.enqueueOutputElement(elem)
        pendingElement = NoPending
      case PendingElementForNewStream(elem) ⇒
        val substreamOutput = newSubstream()
        primaryOutputs.enqueueOutputElement(substreamOutput.processor)
        currentSubstream = substreamOutput
        pendingElement = PendingElement(elem)
    }

    pendingElement match {
      case NoPending                     ⇒ primaryInputs.NeedsInput
      case PendingElement(_)             ⇒ currentSubstream.NeedsDemand
      case PendingElementForNewStream(_) ⇒ primaryOutputs.NeedsDemand
    }
  }

  override def invalidateSubstream(substream: ActorRef): Unit = {
    pendingElement match {
      case PendingElement(_) if substream == currentSubstream.substream ⇒
        setTransferState(primaryInputs.NeedsInput)
        pendingElement = NoPending
      case _ ⇒
    }
    super.invalidateSubstream(substream)
  }

}
