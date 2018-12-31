/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.Member
import akka.cluster.UniqueAddress
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PruningState {
  final case class PruningInitialized(owner: UniqueAddress, seen: Set[Address]) extends PruningState {
    override def addSeen(node: Address): PruningState = {
      if (seen(node) || owner.address == node) this
      else copy(seen = seen + node)
    }
  }
  final case class PruningPerformed(obsoleteTime: Long) extends PruningState {
    def isObsolete(currentTime: Long): Boolean = obsoleteTime <= currentTime
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] sealed trait PruningState {
  import PruningState._

  def merge(that: PruningState): PruningState =
    (this, that) match {
      case (p1: PruningPerformed, p2: PruningPerformed) ⇒ if (p1.obsoleteTime >= p2.obsoleteTime) this else that
      case (_: PruningPerformed, _)                     ⇒ this
      case (_, _: PruningPerformed)                     ⇒ that
      case (PruningInitialized(thisOwner, thisSeen), PruningInitialized(thatOwner, thatSeen)) ⇒
        if (thisOwner == thatOwner)
          PruningInitialized(thisOwner, thisSeen union thatSeen)
        else if (Member.addressOrdering.compare(thisOwner.address, thatOwner.address) > 0)
          that
        else
          this
    }

  def addSeen(node: Address): PruningState = this
}

