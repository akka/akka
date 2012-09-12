/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor.ActorSystem
import akka.event.Logging
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Map

/**
 * A lock-less thread-safe implementation of [[akka.remote.FailureDetectorRegistry]].
 *
 * @param system
 *   Belongs to the [[akka.actor.ActorSystem]]. Used for logging.
 *
 * @param detectorFactory
 *   By-name parameter that returns the failure detector instance to be used by a newly registered resource
 *
 */
class DefaultFailureDetectorRegistry[A](
  val system: ActorSystem,
  val detectorFactory: () ⇒ FailureDetector) extends FailureDetectorRegistry[A] {

  private val log = Logging(system, "FailureDetector")

  // Contains a versioned map. Is version really needed?
  private val table = new AtomicReference[(Long, Map[A, FailureDetector])]((0, Map()))

  /**
   * Returns true if the resource is considered to be up and healthy and returns false otherwise. For unregistered
   * resources it returns true.
   */
  final override def isAvailable(resource: A): Boolean = {
    table.get._2.get(resource) map { _.isAvailable } getOrElse true
  }

  @tailrec
  final override def heartbeat(resource: A): Unit = {
    log.debug("Heartbeat from resource [{}] ", resource)
    val oldTableAndVersion = table.get
    oldTableAndVersion._2.get(resource) match {
      case Some(failureDetector) ⇒ failureDetector.heartbeat()
      case None ⇒
        val newTableAndVersion = (oldTableAndVersion._1 + 1, oldTableAndVersion._2 + (resource -> detectorFactory()))
        if (!table.compareAndSet(oldTableAndVersion, newTableAndVersion)) heartbeat(resource)
    }
  }

  @tailrec
  final override def remove(resource: A): Unit = {
    log.debug("Remove resource [{}] ", resource)
    val oldTableAndVersion = table.get

    if (oldTableAndVersion._2.contains(resource)) {
      val newTableAndVersion = (oldTableAndVersion._1 + 1, oldTableAndVersion._2 - resource)

      // if we won the race then update else try again
      if (!table.compareAndSet(oldTableAndVersion, newTableAndVersion)) remove(resource) // recur
    }
  }

  final override def reset(): Unit = {
    @tailrec
    def doReset(): Unit = {
      val oldTableAndVersion = table.get
      val newTableAndVersion = (oldTableAndVersion._1 + 1, Map.empty[A, FailureDetector])
      // if we won the race then update else try again
      if (!table.compareAndSet(oldTableAndVersion, newTableAndVersion)) doReset() // recur
    }
    log.debug("Resetting failure detector registry")
    doReset()
  }
}

