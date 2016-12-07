/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.Member
import akka.cluster.UniqueAddress

/**
 * INTERNAL API
 */
private[akka] object PruningState {
  sealed trait PruningPhase
  final case class PruningInitialized(seen: Set[Address]) extends PruningPhase
  case object PruningPerformed extends PruningPhase
}

/**
 * INTERNAL API
 */
private[akka] final case class PruningState(owner: UniqueAddress, phase: PruningState.PruningPhase) {
  import PruningState._

  def merge(that: PruningState): PruningState =
    (this.phase, that.phase) match {
      // FIXME this will add the PruningPerformed back again when one is None
      case (PruningPerformed, _) ⇒ this
      case (_, PruningPerformed) ⇒ that
      case (PruningInitialized(thisSeen), PruningInitialized(thatSeen)) ⇒
        if (this.owner == that.owner)
          copy(phase = PruningInitialized(thisSeen union thatSeen))
        else if (Member.addressOrdering.compare(this.owner.address, that.owner.address) > 0)
          that
        else
          this
    }

  def addSeen(node: Address): PruningState = phase match {
    case PruningInitialized(seen) ⇒
      if (seen(node) || owner.address == node) this
      else copy(phase = PruningInitialized(seen + node))
    case _ ⇒ this
  }
}

