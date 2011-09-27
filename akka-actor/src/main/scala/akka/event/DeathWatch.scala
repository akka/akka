/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.config.Supervision.{ FaultHandlingStrategy }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import akka.actor._
import akka.dispatch.SystemEnvelope

trait DeathWatch {
  def signal(fail: Failed, supervisor: ActorRef): Unit
  def signal(terminated: Terminated): Unit
}

trait Monitoring {

  def link(monitor: ActorRef, monitored: ActorRef): Unit

  def unlink(monitor: ActorRef, monitored: ActorRef): Unit
}

object DumbMonitoring extends DeathWatch with Monitoring {

  val monitoring = new akka.util.Index[ActorRef, ActorRef] //Key == monitored, Values == monitors

  def signal(fail: Failed, supervisor: ActorRef): Unit =
    supervisor match {
      case l: LocalActorRef ⇒ l ! fail //FIXME, should Failed be a system message ? => l.underlying.dispatcher.systemDispatch(SystemEnvelope(l.underlying, fail, NullChannel))
      case other            ⇒ throw new IllegalStateException("Supervision only works for local actors currently")
    }

  def signal(terminated: Terminated): Unit = {
    val monitors = monitoring.remove(terminated.actor)
    if (monitors.isDefined)
      monitors.get.foreach(_ ! terminated)
  }

  def link(monitor: ActorRef, monitored: ActorRef): Unit = {
    if (monitored.isShutdown) monitor ! Terminated(monitored, new ActorKilledException("Already terminated when linking"))
    else { // FIXME race between shutting down
      monitoring.put(monitored, monitor)
    }
  }

  def unlink(monitor: ActorRef, monitored: ActorRef): Unit = {
    monitoring.remove(monitored, monitor)
  }
}

/*
* Scenarios that can occur:
*
* Child dies without supervisor (will perhaps not be possible)
* Child dies whose supervisor is dead (race)
* Child dies, supervisor cannot deal with the problem and has no supervisor (will perhaps not be possible)
* Child dies, supervisor cannot deal with the problem and its supervisor is dead (race)
* Child dies, supervisor can deal with it: AllForOnePermanentStrategy
* Child dies, supervisor can deal with it: AllForOnePermanentStrategy but has reached max restart quota for child
* Child dies, supervisor can deal with it: AllForOneTemporaryStrategy
* Multiple children dies, supervisor can deal with it: AllForOnePermanentStrategy
* Multiple children dies, supervisor can deal with it: AllForOneTemporaryStrategy
* Child dies, supervisor can deal with it: OneForOnePermanentStrategy
* Child dies, supervisor can deal with it: OneForOneTemporaryStrategy
*
* Things that should be cleared after restart
*   - monitored children (not supervised)
*
* Things that should be cleared after resume
*   - nothing
*
* Things that should be cleared after death
*   - everything
*
* Default implementation of preRestart == postStop
* Default implementation of postRestart == preStart
*
* */ 