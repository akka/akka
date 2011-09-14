/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

/*package akka.event

import akka.actor.{ Death, ActorRef }
import akka.config.Supervision.{ FaultHandlingStrategy }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

trait DeathWatch {
  def signal(death: Death): Unit
}

object Supervision {
  case class ActiveEntry(monitoring: Vector[ActorRef] = Vector(), supervising: Vector[ActorRef] = Vector(), strategy: FaultHandlingStrategy)
  case class PassiveEntry(monitors: Vector[ActorRef] = Vector(), supervisor: Option[ActorRef] = None)
}

trait Supervision { self: DeathWatch =>

  import Supervision._

  val guard = new ReentrantReadWriteLock
  val read = guard.readLock()
  val write = guard.writeLock()

  val activeEntries  = new ConcurrentHashMap[ActorRef, ActiveEntry](1024)
  val passiveEntries = new ConcurrentHashMap[ActorRef, PassiveEntry](1024)

  def registerMonitorable(monitor: ActorRef, monitorsSupervisor: Option[ActorRef], faultHandlingStrategy: FaultHandlingStrategy): Unit = {
    read.lock()
    try {
      activeEntries.putIfAbsent(monitor, ActiveEntry(strategy = faultHandlingStrategy))
      passiveEntries.putIfAbsent(monitor, PassiveEntry(supervisor = monitorsSupervisor))
    } finally {
      read.unlock()
    }
  }

  def deregisterMonitorable(monitor: ActorRef): Unit = {
    read.lock()
    try {
      activeEntries.remove(monitor)
      passiveEntries.remove(monitor)
    } finally {
      read.unlock()
    }
  }

  def startMonitoring(monitor: ActorRef, monitored: ActorRef): ActorRef = {
    def addActiveEntry(): ActorRef =
      activeEntries.get(monitor) match {
        case null => null//He's stopped or not started, which is unlikely
        case entry =>
          val updated = entry.copy(monitoring = entry.monitoring :+ monitored)
          if (activeEntries.replace(monitor, entry, updated))
            monitored
          else
            addActiveEntry()
      }

    def addPassiveEntry(): ActorRef =
      activeEntries.get(monitored) match {
        case null => null//The thing we're trying to monitor isn't registered, abort
        case _ =>
          passiveEntries.get(monitored) match {
            case null =>
              passiveEntries.putIfAbsent(monitored, PassiveEntry(monitors = Vector(monitor))) match {
                case null => monitored//All good
                case _ => addPassiveEntry()
              }

            case existing =>
              val updated = existing.copy(monitors = existing.monitors :+ monitor)
              if (passiveEntries.replace(monitored, existing, updated))
                monitored
              else
                addPassiveEntry()
          }
    }

    read.lock()
    try {
      addActiveEntry()
      addPassiveEntry()
    } finally {
      read.unlock()
    }
  }

  def stopMonitoring(monitor: ActorRef, monitored: ActorRef, strategy: FaultHandlingStrategy, supervise: Boolean): ActorRef = {
    monitored
  }
}

class Scenarios {
  ""
}*/ 