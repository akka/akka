/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Member
import akka.cluster.UniqueAddress
import akka.actor.Address

/**
 * INTERNAL API
 */
private[akka] object PruningState {
  sealed trait PruningPhase
  case class PruningInitialized(seen: Set[Address]) extends PruningPhase
  case object PruningPerformed extends PruningPhase
}

/**
 * INTERNAL API
 */
private[akka] case class PruningState(owner: UniqueAddress, phase: PruningState.PruningPhase) {
  import PruningState._

  def merge(that: PruningState): PruningState =
    (this.phase, that.phase) match {
      case (PruningPerformed, _) ⇒ this
      case (_, PruningPerformed) ⇒ that
      case (PruningInitialized(thisSeen), PruningInitialized(thatSeen)) ⇒
        if (this.owner == that.owner)
          copy(phase = PruningInitialized(thisSeen ++ thatSeen))
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

