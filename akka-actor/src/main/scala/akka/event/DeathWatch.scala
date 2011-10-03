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

  class MonitoringBook(mapSize: Int = 1024) {
    import java.util.concurrent.ConcurrentHashMap
    import scala.annotation.tailrec

    object Tombstone
    val mappings = new ConcurrentHashMap[ActorRef, AnyRef](mapSize)

    @tailrec
    final def associate(monitored: ActorRef, monitor: ActorRef): Boolean = {
      val current = mappings get monitored
      current match {
        case null ⇒
          if (monitored.isShutdown) false
          else {
            if (mappings.putIfAbsent(monitored, Vector(monitor)) ne null) associate(monitored, monitor)
            else true
          }
        case Tombstone ⇒ false
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          if (monitored.isShutdown) false
          if (v.contains(monitor)) true
          else {
            val added = v :+ monitor
            if (!mappings.replace(monitored, v, added)) associate(monitored, monitor)
            else true
          }
      }
    }

    @tailrec
    final def dissociate(monitored: ActorRef): Iterable[ActorRef] = {
      val current = mappings get monitored
      current match {
        case null | Tombstone ⇒ Vector.empty[ActorRef]
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          if (!mappings.replace(monitored, v, Tombstone)) dissociate(monitored)
          else {
            assert(mappings.remove(monitored, Tombstone))
            v
          }
      }
    }

    @tailrec
    final def dissociate(monitored: ActorRef, monitor: ActorRef): Unit = {
      val current = mappings get monitored
      current match {
        case null | Tombstone ⇒ Vector.empty[ActorRef]
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          val removed = v.filterNot(monitor ==)
          if (removed.isEmpty) {
            if (!mappings.remove(monitored, v)) dissociate(monitored, monitor)
            else ()
          } else {
            if (!mappings.replace(monitored, v, removed)) dissociate(monitored, monitor)
            else ()
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