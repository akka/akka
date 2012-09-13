/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.event.LoggingAdapter
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Map

/**
 * A lock-less thread-safe implementation of [[akka.remote.FailureDetectorRegistry]].
 *
 * @param log
 *   Logger to be used by the registry for logging.
 *
 * @param detectorFactory
 *   By-name parameter that returns the failure detector instance to be used by a newly registered resource
 *
 */
class DefaultFailureDetectorRegistry[A](
  val log: LoggingAdapter,
  val detectorFactory: () ⇒ FailureDetector) extends FailureDetectorRegistry[A] {

  private val table = new AtomicReference[Map[A, FailureDetector]](Map())

  /**
   * Returns true if the resource is considered to be up and healthy and returns false otherwise. For unregistered
   * resources it returns true.
   */
  final override def isAvailable(resource: A): Boolean = {
    table.get.get(resource) map { _.isAvailable } getOrElse true
  }

  final override def heartbeat(resource: A): Unit = {

    // Second option parameter is there to avoid the unnecessary creation of failure detectors when a CAS loop happens
    // Note, _one_ unnecessary detector might be created -- but no more.
    @tailrec
    def doHeartbeat(resource: A, detector: Option[FailureDetector]): Unit = {
      val oldTable = table.get

      oldTable.get(resource) match {
        case Some(failureDetector) ⇒ failureDetector.heartbeat()
        case None ⇒
          val newDetector = detector getOrElse detectorFactory()
          val newTable = oldTable + (resource -> newDetector)
          if (!table.compareAndSet(oldTable, newTable)) {
            doHeartbeat(resource, Some(newDetector))
          } else {
            // successfully registered a new detector, now fire first heartbeat
            newDetector.heartbeat()
          }
      }
    }

    log.debug("Heartbeat from resource [{}] ", resource)
    doHeartbeat(resource, None)
  }

  final override def remove(resource: A): Unit = {

    @tailrec
    def doRemove(resource: A): Unit = {
      val oldTable = table.get

      if (oldTable.contains(resource)) {
        val newTable = oldTable - resource

        // if we won the race then update else try again
        if (!table.compareAndSet(oldTable, newTable)) doRemove(resource) // recur
      }
    }

    log.debug("Remove resource [{}] ", resource)
    doRemove(resource)
  }

  final override def reset(): Unit = {

    @tailrec
    def doReset(): Unit = {
      val oldTable = table.get
      // if we won the race then update else try again
      if (!table.compareAndSet(oldTable, Map.empty[A, FailureDetector])) doReset() // recur
    }

    log.debug("Resetting failure detector registry")
    doReset()
  }
}

