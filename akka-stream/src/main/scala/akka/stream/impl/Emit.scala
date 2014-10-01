/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[akka] trait Emit { this: ActorProcessorImpl with Pump ⇒

  // TODO performance improvement: mutable buffer?
  var emits = immutable.Seq.empty[Any]

  // Save previous phase we should return to in a var to avoid allocation
  private var phaseAfterFlush: TransferPhase = _

  // Enters flushing phase if there are emits pending
  def emitAndThen(andThen: TransferPhase): Unit =
    if (emits.nonEmpty) {
      phaseAfterFlush = andThen
      nextPhase(emitting)
    } else nextPhase(andThen)

  // Emits all pending elements, then returns to savedPhase
  private val emitting = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(emits.head)
    emits = emits.tail
    if (emits.isEmpty) nextPhase(phaseAfterFlush)
  }

}
