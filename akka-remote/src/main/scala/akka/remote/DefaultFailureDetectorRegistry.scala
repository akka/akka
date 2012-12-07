/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Map
import java.util.concurrent.locks.{ ReentrantLock, Lock }

/**
 * A lock-less thread-safe implementation of [[akka.remote.FailureDetectorRegistry]].
 *
 * @param detectorFactory
 *   By-name parameter that returns the failure detector instance to be used by a newly registered resource
 *
 */
class DefaultFailureDetectorRegistry[A](val detectorFactory: () ⇒ FailureDetector) extends FailureDetectorRegistry[A] {

  private val resourceToFailureDetector = new AtomicReference[Map[A, FailureDetector]](Map())
  private final val failureDectorCreationLock: Lock = new ReentrantLock

  /**
   * Returns true if the resource is considered to be up and healthy and returns false otherwise. For unregistered
   * resources it returns true.
   */
  final override def isAvailable(resource: A): Boolean = resourceToFailureDetector.get.get(resource) match {
    case Some(r) ⇒ r.isAvailable
    case _       ⇒ true
  }

  @tailrec final override def heartbeat(resource: A): Unit = {

    val oldTable = resourceToFailureDetector.get

    oldTable.get(resource) match {
      case Some(failureDetector) ⇒ failureDetector.heartbeat()
      case None ⇒
        // First one wins and creates the new FailureDetector
        if (failureDectorCreationLock.tryLock()) try {
          val newDetector: FailureDetector = detectorFactory()
          newDetector.heartbeat()
          resourceToFailureDetector.set(oldTable + (resource -> newDetector))
        } finally failureDectorCreationLock.unlock()
        else heartbeat(resource) // The thread that lost the race will try to reread
    }
  }

  @tailrec final override def remove(resource: A): Unit = {

    val oldTable = resourceToFailureDetector.get

    if (oldTable.contains(resource)) {
      val newTable = oldTable - resource

      // if we won the race then update else try again
      if (!resourceToFailureDetector.compareAndSet(oldTable, newTable)) remove(resource) // recur
    }
  }

  @tailrec final override def reset(): Unit = {

    val oldTable = resourceToFailureDetector.get
    // if we won the race then update else try again
    if (!resourceToFailureDetector.compareAndSet(oldTable, Map.empty[A, FailureDetector])) reset() // recur

  }
}

