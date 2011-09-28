/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor._

trait DeathWatch {
  def signal(terminated: Terminated): Unit
}

trait Monitoring {

  def link(monitor: ActorRef, monitored: ActorRef): Unit

  def unlink(monitor: ActorRef, monitored: ActorRef): Unit
}

object DumbMonitoring extends DeathWatch with Monitoring {

  val monitoring = new akka.util.Index[ActorRef, ActorRef] //Key == monitored, Values == monitors

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