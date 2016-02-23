/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import scala.util.control.NonFatal
import akka.actor.{ Deploy, Props }
import akka.stream.ActorMaterializerSettings
import akka.stream.Supervision
import akka.stream.scaladsl.Source

/**
 * INTERNAL API
 */
private[akka] object GroupByProcessorImpl {
  def props(settings: ActorMaterializerSettings, maxSubstreams: Int, keyFor: Any ⇒ Any): Props =
    Props(new GroupByProcessorImpl(settings, maxSubstreams, keyFor)).withDeploy(Deploy.local)

  private case object Drop
}

/**
 * INTERNAL API
 */
private[akka] class GroupByProcessorImpl(settings: ActorMaterializerSettings, val maxSubstreams: Int, val keyFor: Any ⇒ Any)
  extends MultiStreamOutputProcessor(settings) {

  import MultiStreamOutputProcessor._
  import GroupByProcessorImpl.Drop

  val decider = settings.supervisionDecider
  val keyToSubstreamOutput = collection.mutable.Map.empty[Any, SubstreamOutput]

  var pendingSubstreamOutput: SubstreamOutput = _

  // No substream is open yet. If downstream cancels now, we are complete
  val waitFirst = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    tryKeyFor(elem) match {
      case Drop ⇒
      case key  ⇒ nextPhase(openSubstream(elem, key))
    }
  }

  // some substreams are open now. If downstream cancels, we still continue until the substreams are closed
  val waitNext = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    tryKeyFor(elem) match {
      case Drop ⇒
      case key ⇒
        keyToSubstreamOutput.get(key) match {
          case Some(substream) if substream.isOpen ⇒ nextPhase(dispatchToSubstream(elem, keyToSubstreamOutput(key)))
          case None if primaryOutputs.isOpen       ⇒ nextPhase(openSubstream(elem, key))
          case _                                   ⇒ // stay
        }
    }
  }

  private def tryKeyFor(elem: Any): Any =
    try keyFor(elem) catch {
      case NonFatal(e) if decider(e) != Supervision.Stop ⇒
        if (settings.debugLogging)
          log.debug("Dropped element [{}] due to exception from groupBy function: {}", elem, e.getMessage)
        Drop
    }

  def openSubstream(elem: Any, key: Any): TransferPhase = TransferPhase(primaryOutputs.NeedsDemandOrCancel) { () ⇒
    if (primaryOutputs.isClosed) {
      // Just drop, we do not open any more substreams
      nextPhase(waitNext)
    } else {
      if (keyToSubstreamOutput.size == maxSubstreams)
        throw new IllegalStateException(s"cannot open substream for key '$key': too many substreams open")
      val substreamOutput = createSubstreamOutput()
      val substreamFlow = Source.fromPublisher[Any](substreamOutput)
      primaryOutputs.enqueueOutputElement(substreamFlow)
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

  initialPhase(1, waitFirst)

  override def invalidateSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((pendingSubstreamOutput ne null) && substream == pendingSubstreamOutput.key) {
      pendingSubstreamOutput = null
      nextPhase(waitNext)
    }
    super.invalidateSubstreamOutput(substream)
  }

}
