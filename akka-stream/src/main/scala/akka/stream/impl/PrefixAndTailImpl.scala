/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Source
import akka.actor.{ Deploy, Props }

/**
 * INTERNAL API
 */
private[akka] object PrefixAndTailImpl {
  def props(settings: ActorMaterializerSettings, takeMax: Int): Props =
    Props(new PrefixAndTailImpl(settings, takeMax)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] class PrefixAndTailImpl(_settings: ActorMaterializerSettings, val takeMax: Int)
  extends MultiStreamOutputProcessor(_settings) {

  import MultiStreamOutputProcessor._

  var taken = immutable.Vector.empty[Any]
  var left = takeMax

  val take = TransferPhase(primaryInputs.NeedsInputOrComplete && primaryOutputs.NeedsDemand) { () ⇒
    if (primaryInputs.inputsDepleted) emitEmptyTail()
    else {
      val elem = primaryInputs.dequeueInputElement()
      taken :+= elem
      left -= 1
      if (left <= 0) {
        if (primaryInputs.inputsDepleted) emitEmptyTail()
        else emitNonEmptyTail()
      }
    }
  }

  def streamTailPhase(substream: SubstreamOutput) = TransferPhase(primaryInputs.NeedsInput && substream.NeedsDemand) { () ⇒
    substream.enqueueOutputElement(primaryInputs.dequeueInputElement())
  }

  val takeEmpty = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    if (primaryInputs.inputsDepleted) emitEmptyTail()
    else emitNonEmptyTail()
  }

  def emitEmptyTail(): Unit = {
    primaryOutputs.enqueueOutputElement((taken, Source.empty))
    nextPhase(completedPhase)
  }

  def emitNonEmptyTail(): Unit = {
    val substreamOutput = createSubstreamOutput()
    val substreamFlow = Source(substreamOutput) // substreamOutput is a Publisher
    primaryOutputs.enqueueOutputElement((taken, substreamFlow))
    primaryOutputs.complete()
    nextPhase(streamTailPhase(substreamOutput))
  }

  if (takeMax > 0) initialPhase(1, take) else initialPhase(1, takeEmpty)
}
