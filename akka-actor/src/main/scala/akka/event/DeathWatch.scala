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

object InVMMonitoring extends DeathWatch with Monitoring {

  class MonitoringBook(mapSize: Int = 1024) {
    import java.util.concurrent.ConcurrentHashMap
    import scala.annotation.tailrec

    val mappings = new ConcurrentHashMap[ActorRef, Vector[ActorRef]](mapSize)

    @tailrec
    final def associate(monitored: ActorRef, monitor: ActorRef): Boolean = {
      val current = mappings get monitored
      current match {
        case null ⇒
          if (monitored.isShutdown) false
          else {
            if (mappings.putIfAbsent(monitored, Vector(monitor)) ne null) associate(monitored, monitor)
            else {
              if (monitored.isShutdown) !dissociate(monitored, monitor)
              else true
            }
          }
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          if (monitored.isShutdown) false
          if (v.contains(monitor)) true
          else {
            val added = v :+ monitor
            if (!mappings.replace(monitored, v, added)) associate(monitored, monitor)
            else {
              if (monitored.isShutdown) !dissociate(monitored, monitor)
              else true
            }
          }
      }
    }

    final def dissociate(monitored: ActorRef): Iterable[ActorRef] = {
      @tailrec
      def dissociateAsMonitored(monitored: ActorRef): Iterable[ActorRef] = {
        val current = mappings get monitored
        current match {
          case null ⇒ Vector.empty[ActorRef]
          case raw: Vector[_] ⇒
            val v = raw.asInstanceOf[Vector[ActorRef]]
            if (!mappings.remove(monitored, v)) dissociateAsMonitored(monitored)
            else v
        }
      }

      def dissociateAsMonitor(monitor: ActorRef): Unit = {
        val i = mappings.entrySet.iterator
        while (i.hasNext()) {
          val entry = i.next()
          val v = entry.getValue
          v match {
            case raw: Vector[_] ⇒
              val monitors = raw.asInstanceOf[Vector[ActorRef]]
              if (monitors.contains(monitor))
                dissociate(entry.getKey, monitor)
            case _ ⇒ //Dun care
          }
        }
      }

      try { dissociateAsMonitored(monitored) } finally { dissociateAsMonitor(monitored) }
    }

    @tailrec
    final def dissociate(monitored: ActorRef, monitor: ActorRef): Boolean = {
      val current = mappings get monitored
      current match {
        case null ⇒ false
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          val removed = v.filterNot(monitor ==)
          if (removed eq v) false
          else if (removed.isEmpty) {
            if (!mappings.remove(monitored, v)) dissociate(monitored, monitor)
            else true
          } else {
            if (!mappings.replace(monitored, v, removed)) dissociate(monitored, monitor)
            else true
          }
      }
    }
  }

  val monitoring = new MonitoringBook(1024) //Key == monitored, Values == monitors

  def signal(terminated: Terminated): Unit = {
    val monitors = monitoring.dissociate(terminated.actor)
    if (monitors.nonEmpty) monitors.foreach(_ ! terminated)
  }

  def link(monitor: ActorRef, monitored: ActorRef): Unit = {
    if (!monitoring.associate(monitored, monitor))
      monitor ! Terminated(monitored, new ActorKilledException("Already terminated when linking"))
  }

  def unlink(monitor: ActorRef, monitored: ActorRef): Unit =
    monitoring.dissociate(monitored, monitor)
}