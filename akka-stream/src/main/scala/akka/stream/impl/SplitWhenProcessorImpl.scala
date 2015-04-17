/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.util.control.NonFatal
import akka.actor.Props
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.Supervision
import akka.stream.scaladsl.Source

/**
 * INTERNAL API
 */
private[akka] object SplitWhenProcessorImpl {
  def props(settings: ActorFlowMaterializerSettings, splitPredicate: Any ⇒ Boolean): Props =
    Props(new SplitWhenProcessorImpl(settings, splitPredicate))

  private trait SplitDecision
  private case object Split extends SplitDecision
  private case object Continue extends SplitDecision
  private case object Drop extends SplitDecision
}

/**
 * INTERNAL API
 */
private[akka] class SplitWhenProcessorImpl(_settings: ActorFlowMaterializerSettings, val splitPredicate: Any ⇒ Boolean)
  extends MultiStreamOutputProcessor(_settings) {

  import MultiStreamOutputProcessor._
  import SplitWhenProcessorImpl._

  val decider = settings.supervisionDecider
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
    decideSplit(elem) match {
      case Continue ⇒ substream.enqueueOutputElement(elem)
      case Split ⇒
        completeSubstreamOutput(currentSubstream.key)
        currentSubstream = null
        nextPhase(openSubstream(elem))
      case Drop ⇒ // drop elem and continue
    }
  }

  // Ignore elements for a cancelled substream until a new substream needs to be opened
  val ignoreUntilNewSubstream = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    decideSplit(elem) match {
      case Continue | Drop ⇒ // ignore elem
      case Split           ⇒ nextPhase(openSubstream(elem))
    }
  }

  private def decideSplit(elem: Any): SplitDecision =
    try if (splitPredicate(elem)) Split else Continue catch {
      case NonFatal(e) if decider(e) != Supervision.Stop ⇒
        if (settings.debugLogging)
          log.debug("Dropped element [{}] due to exception from splitWhen function: {}", elem, e.getMessage)
        Drop
    }

  initialPhase(1, waitFirst)

  override def completeSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
    super.completeSubstreamOutput(substream)
  }

  override def cancelSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
    super.cancelSubstreamOutput(substream)
  }

}
