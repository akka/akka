/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor.{ Death, LocalActorRef, ActorRef }

trait DeathWatch {
  def signal(death: Death): Unit
}

class StupidInVMDeathWatchImpl extends DeathWatch {
  def signal(death: Death) {
    death match {
      case c @ Death(victim: LocalActorRef, _, _) if victim.supervisor.isDefined ⇒
        victim.supervisor.get ! c

      case other ⇒ EventHandler.debug(this, "No supervisor or not a local actor reference: " + other)
    }
  }
}