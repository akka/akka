/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit

case class ChildRestartStats(val child: ActorRef, var maxNrOfRetriesCount: Int = 0, var restartTimeWindowStartNanos: Long = 0L) {
  def requestRestartPermission(maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Boolean = {
    val denied = if (maxNrOfRetries.isEmpty && withinTimeRange.isEmpty)
      false // Never deny an immortal
    else if (maxNrOfRetries.nonEmpty && maxNrOfRetries.get < 1)
      true //Always deny if no chance of restarting
    else if (withinTimeRange.isEmpty) {
      // restrict number of restarts
      val retries = maxNrOfRetriesCount + 1
      maxNrOfRetriesCount = retries //Increment number of retries
      retries > maxNrOfRetries.get
    } else {
      // cannot restart more than N within M timerange
      val retries = maxNrOfRetriesCount + 1

      val windowStart = restartTimeWindowStartNanos
      val now = System.nanoTime
      // we are within the time window if it isn't the first restart, or if the window hasn't closed
      val insideWindow = if (windowStart == 0) true else (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(withinTimeRange.get)

      if (windowStart == 0 || !insideWindow) //(Re-)set the start of the window
        restartTimeWindowStartNanos = now

      // reset number of restarts if window has expired, otherwise, increment it
      maxNrOfRetriesCount = if (windowStart != 0 && !insideWindow) 1 else retries // increment number of retries

      val restartCountLimit = if (maxNrOfRetries.isDefined) maxNrOfRetries.get else 1

      // the actor is dead if it dies X times within the window of restart
      insideWindow && retries > restartCountLimit
    }

    denied == false // if we weren't denied, we have a go
  }
}

sealed abstract class FaultHandlingStrategy {

  def trapExit: List[Class[_ <: Throwable]]

  def handleChildTerminated(child: ActorRef, children: Vector[ChildRestartStats]): Vector[ChildRestartStats]

  def processFailure(fail: Failed, children: Vector[ChildRestartStats]): Unit

  def handleSupervisorFailing(supervisor: ActorRef, children: Vector[ChildRestartStats]): Unit = {
    if (children.nonEmpty)
      children.foreach(_.child.suspend())
  }

  def handleSupervisorRestarted(cause: Throwable, supervisor: ActorRef, children: Vector[ChildRestartStats]): Unit = {
    if (children.nonEmpty)
      children.foreach(_.child.restart(cause))
  }

  /**
   * Returns whether it processed the failure or not
   */
  final def handleFailure(fail: Failed, children: Vector[ChildRestartStats]): Boolean = {
    if (trapExit.exists(_.isAssignableFrom(fail.cause.getClass))) {
      processFailure(fail, children)
      true
    } else false
  }
}

object AllForOneStrategy {
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): AllForOneStrategy =
    new AllForOneStrategy(trapExit, if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
}

/**
 * Restart all actors linked to the same supervisor when one fails,
 * trapExit = which Throwables should be intercepted
 * maxNrOfRetries = the number of times an actor is allowed to be restarted
 * withinTimeRange = millisecond time window for maxNrOfRetries, negative means no window
 */
case class AllForOneStrategy(trapExit: List[Class[_ <: Throwable]],
                             maxNrOfRetries: Option[Int] = None,
                             withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy {
  def this(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit,
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit.toList,
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: java.util.List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit.toArray.toList.asInstanceOf[List[Class[_ <: Throwable]]],
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

  def handleChildTerminated(child: ActorRef, children: Vector[ChildRestartStats]): Vector[ChildRestartStats] = {
    children collect {
      case stats if stats.child != child ⇒ stats.child.stop(); stats //2 birds with one stone: remove the child + stop the other children
    } //TODO optimization to drop all children here already?
  }

  def processFailure(fail: Failed, children: Vector[ChildRestartStats]): Unit = {
    if (children.nonEmpty) {
      if (children.forall(_.requestRestartPermission(maxNrOfRetries, withinTimeRange)))
        children.foreach(_.child.restart(fail.cause))
      else
        children.foreach(_.child.stop())
    }
  }
}

object OneForOneStrategy {
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): OneForOneStrategy =
    new OneForOneStrategy(trapExit, if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
}

/**
 * Restart an actor when it fails
 * trapExit = which Throwables should be intercepted
 * maxNrOfRetries = the number of times an actor is allowed to be restarted
 * withinTimeRange = millisecond time window for maxNrOfRetries, negative means no window
 */
case class OneForOneStrategy(trapExit: List[Class[_ <: Throwable]],
                             maxNrOfRetries: Option[Int] = None,
                             withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy {
  def this(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit,
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit.toList,
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: java.util.List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit.toArray.toList.asInstanceOf[List[Class[_ <: Throwable]]],
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

  def handleChildTerminated(child: ActorRef, children: Vector[ChildRestartStats]): Vector[ChildRestartStats] =
    children.filterNot(_.child == child)

  def processFailure(fail: Failed, children: Vector[ChildRestartStats]): Unit = {
    children.find(_.child == fail.actor) match {
      case Some(stats) ⇒
        if (stats.requestRestartPermission(maxNrOfRetries, withinTimeRange))
          fail.actor.restart(fail.cause)
        else
          fail.actor.stop() //TODO optimization to drop child here already?
      case None ⇒ throw new AssertionError("Got Failure from non-child: " + fail)
    }
  }
}

