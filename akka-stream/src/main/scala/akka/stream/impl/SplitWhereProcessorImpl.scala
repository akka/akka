/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.{ Deploy, Props }
import akka.stream.impl.SplitDecision.SplitDecision
import akka.stream.scaladsl.Source
import akka.stream.{ ActorMaterializerSettings, Supervision }

import scala.util.control.NonFatal

/** INTERNAL API */
private[akka] object SplitDecision {
  sealed abstract class SplitDecision

  /** Splits before the current element. The current element will be the first element in the new substream. */
  case object SplitBefore extends SplitDecision

  /** Splits after the current element. The current element will be the last element in the current substream. */
  case object SplitAfter extends SplitDecision

  /** Emit this element into the current substream. */
  case object Continue extends SplitDecision

  /**
   * Drop this element without signalling it to any substream.
   * TODO: Dropping is currently not exposed in an usable way - we would have to expose splitWhen(x => SplitDecision), to be decided if we want this
   */
  private[impl] case object Drop extends SplitDecision
}

/**
 * INTERNAL API
 */
private[akka] object SplitWhereProcessorImpl {
  def props(settings: ActorMaterializerSettings, splitPredicate: Any ⇒ SplitDecision): Props =
    Props(new SplitWhereProcessorImpl(settings, in ⇒ splitPredicate(in))).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] class SplitWhereProcessorImpl(_settings: ActorMaterializerSettings, val splitPredicate: Any ⇒ SplitDecision)
  extends MultiStreamOutputProcessor(_settings) {

  import MultiStreamOutputProcessor._
  import SplitDecision._

  /**
   * `firstElement` is needed in case a SplitBefore is signalled, and the first element matches
   *  We do not want to emit an "empty stream" then followed by the "split", but we do want to start the stream
   *  from the first element (as if no split was applied): [0,1,2,0].splitWhen(_ == 0) => [0,1,2], [0]
   */
  var firstElement = true

  val decider = settings.supervisionDecider
  var currentSubstream: SubstreamOutput = _

  val waitFirst = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    decideSplit(elem) match {
      case Continue    ⇒ nextPhase(openSubstream(serveSubstreamFirst(_, elem)))
      case SplitAfter  ⇒ nextPhase(openSubstream(completeSubstream(_, elem)))
      case SplitBefore ⇒ nextPhase(openSubstream(serveSubstreamFirst(_, elem)))
      case Drop        ⇒ // stay in waitFirst
    }
  }

  private def openSubstream(andThen: SubstreamOutput ⇒ TransferPhase): TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    val substreamOutput = createSubstreamOutput()
    val substreamFlow = Source(substreamOutput) // substreamOutput is a Publisher
    primaryOutputs.enqueueOutputElement(substreamFlow)
    currentSubstream = substreamOutput

    nextPhase(andThen(currentSubstream))
  }

  // Serving the substream is split into two phases to minimize elements "held in hand"
  private def serveSubstreamFirst(substream: SubstreamOutput, elem: Any) = TransferPhase(substream.NeedsDemand) { () ⇒
    firstElement = false
    substream.enqueueOutputElement(elem)
    nextPhase(serveSubstreamRest(substream))
  }

  // Signal given element to substream and complete it
  private def completeSubstream(substream: SubstreamOutput, elem: Any): TransferPhase = TransferPhase(substream.NeedsDemand) { () ⇒
    substream.enqueueOutputElement(elem)
    completeSubstreamOutput(currentSubstream.key)
    nextPhase(waitFirst)
  }

  // Note that this phase is allocated only once per _slice_ and not per element
  private def serveSubstreamRest(substream: SubstreamOutput): TransferPhase = TransferPhase(primaryInputs.NeedsInput && substream.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    decideSplit(elem) match {
      case Continue ⇒
        substream.enqueueOutputElement(elem)

      case SplitAfter ⇒
        substream.enqueueOutputElement(elem)
        completeSubstreamOutput(currentSubstream.key)
        currentSubstream = null
        nextPhase(openSubstream(serveSubstreamRest))

      case SplitBefore if firstElement ⇒
        currentSubstream.enqueueOutputElement(elem)
        completeSubstreamOutput(currentSubstream.key)
        currentSubstream = null
        nextPhase(openSubstream(serveSubstreamRest))

      case SplitBefore ⇒
        completeSubstreamOutput(currentSubstream.key)
        currentSubstream = null
        nextPhase(openSubstream(serveSubstreamFirst(_, elem)))

      case Drop ⇒
      // drop elem and continue
    }
    firstElement = false
  }

  // Ignore elements for a cancelled substream until a new substream needs to be opened
  val ignoreUntilNewSubstream = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    decideSplit(elem) match {
      case Continue | Drop ⇒ // ignore elem
      case SplitBefore     ⇒ nextPhase(openSubstream(serveSubstreamFirst(_, elem)))
      case SplitAfter      ⇒ nextPhase(openSubstream(serveSubstreamRest))
    }
  }

  private def decideSplit(elem: Any): SplitDecision =
    try splitPredicate(elem) catch {
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
