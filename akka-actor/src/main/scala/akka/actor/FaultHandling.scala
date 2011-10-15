/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import java.lang.{ Iterable ⇒ JIterable }

case class ChildRestartStats(val child: ActorRef, var maxNrOfRetriesCount: Int = 0, var restartTimeWindowStartNanos: Long = 0L) {

  def requestRestartPermission(retriesWindow: (Option[Int], Option[Int])): Boolean =
    retriesWindow match {
      case (Some(retries), _) if retries < 1    ⇒ false
      case (Some(retries), None)                ⇒ maxNrOfRetriesCount += 1; maxNrOfRetriesCount <= retries
      case (x @ (Some(_) | None), Some(window)) ⇒ retriesInWindowOkay(if (x.isDefined) x.get else 1, window)
      case (None, _)                            ⇒ true
    }

  private def retriesInWindowOkay(retries: Int, window: Int): Boolean = {
    /*
     * Simple window algorithm: window is kept open for a certain time 
     * after a restart and if enough restarts happen during this time, it 
     * denies. Otherwise window closes and the scheme starts over.
     */
    val retriesDone = maxNrOfRetriesCount + 1
    val now = System.nanoTime
    val windowStart =
      if (restartTimeWindowStartNanos == 0) {
        restartTimeWindowStartNanos = now
        now
      } else restartTimeWindowStartNanos
    val insideWindow = (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(window)
    if (insideWindow) {
      maxNrOfRetriesCount = retriesDone
      retriesDone <= retries
    } else {
      maxNrOfRetriesCount = 1
      restartTimeWindowStartNanos = now
      true
    }
  }
}

object FaultHandlingStrategy {
  sealed trait Action
  case object Resume extends Action
  case object Restart extends Action
  case object Stop extends Action
  case object Escalate extends Action

  type Decider = PartialFunction[Class[_ <: Throwable], Action]
  type JDecider = akka.japi.Function[Class[_ <: Throwable], Action]
  type CauseAction = (Class[_ <: Throwable], Action)

  /**
   * Backwards compatible Decider builder which just checks whether one of
   * the given Throwables matches the cause and restarts, otherwise escalates.
   */
  def makeDecider(trapExit: Array[Class[_ <: Throwable]]): Decider =
    { case x ⇒ if (trapExit exists (_ isAssignableFrom x)) Restart else Escalate }

  /**
   * Backwards compatible Decider builder which just checks whether one of
   * the given Throwables matches the cause and restarts, otherwise escalates.
   */
  def makeDecider(trapExit: List[Class[_ <: Throwable]]): Decider =
    { case x ⇒ if (trapExit exists (_ isAssignableFrom x)) Restart else Escalate }

  /**
   * Backwards compatible Decider builder which just checks whether one of
   * the given Throwables matches the cause and restarts, otherwise escalates.
   */
  def makeDecider(trapExit: JIterable[Class[_ <: Throwable]]): Decider = makeDecider(trapExit.toList)

  /**
   * Decider builder for Iterables of cause-action pairs, e.g. a map obtained
   * from configuration; will sort the pairs so that the most specific type is
   * checked before all its subtypes, allowing carving out subtrees of the
   * Throwable hierarchy.
   */
  def makeDecider(flat: Iterable[CauseAction]): Decider = {
    val actions = sort(flat)
    return { case x ⇒ actions find (_._1 isAssignableFrom x) map (_._2) getOrElse Escalate }
  }

  def makeDecider(func: JDecider): Decider = {
    case x ⇒ func(x)
  }

  /**
   * Sort so that subtypes always precede their supertypes, but without
   * obeying any order between unrelated subtypes (insert sort).
   */
  def sort(in: Iterable[CauseAction]): Seq[CauseAction] =
    (new ArrayBuffer[CauseAction](in.size) /: in) { (buf, ca) ⇒
      buf.indexWhere(_._1 isAssignableFrom ca._1) match {
        case -1 ⇒ buf append ca
        case x  ⇒ buf insert (x, ca)
      }
      buf
    }
}

abstract class FaultHandlingStrategy {

  import FaultHandlingStrategy._

  def decider: Decider

  def handleChildTerminated(child: ActorRef, children: Vector[ChildRestartStats]): Vector[ChildRestartStats]

  def processFailure(restart: Boolean, fail: Failed, children: Vector[ChildRestartStats]): Unit

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
    val cause = fail.cause.getClass
    val action = if (decider.isDefinedAt(cause)) decider(cause) else Escalate
    action match {
      case Resume   ⇒ fail.actor.resume(); true
      case Restart  ⇒ processFailure(true, fail, children); true
      case Stop     ⇒ processFailure(false, fail, children); true
      case Escalate ⇒ false
    }
  }
}

object AllForOneStrategy {
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): AllForOneStrategy =
    new AllForOneStrategy(FaultHandlingStrategy.makeDecider(trapExit),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): AllForOneStrategy =
    new AllForOneStrategy(FaultHandlingStrategy.makeDecider(trapExit), maxNrOfRetries, withinTimeRange)
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Option[Int]): AllForOneStrategy =
    new AllForOneStrategy(FaultHandlingStrategy.makeDecider(trapExit), maxNrOfRetries, None)
}

/**
 * Restart all actors linked to the same supervisor when one fails,
 * trapExit = which Throwables should be intercepted
 * maxNrOfRetries = the number of times an actor is allowed to be restarted
 * withinTimeRange = millisecond time window for maxNrOfRetries, negative means no window
 */
case class AllForOneStrategy(decider: FaultHandlingStrategy.Decider,
                             maxNrOfRetries: Option[Int] = None,
                             withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy {

  def this(decider: FaultHandlingStrategy.JDecider, maxNrOfRetries: Int, withinTimeRange: Int) =
    this(FaultHandlingStrategy.makeDecider(decider),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries),
      if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: JIterable[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(FaultHandlingStrategy.makeDecider(trapExit),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries),
      if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(FaultHandlingStrategy.makeDecider(trapExit),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries),
      if (withinTimeRange < 0) None else Some(withinTimeRange))

  /*
   *  this is a performance optimization to avoid re-allocating the pairs upon 
   *  every call to requestRestartPermission, assuming that strategies are shared
   *  across actors and thus this field does not take up much space
   */
  val retriesWindow = (maxNrOfRetries, withinTimeRange)

  def handleChildTerminated(child: ActorRef, children: Vector[ChildRestartStats]): Vector[ChildRestartStats] = {
    children collect {
      case stats if stats.child != child ⇒ stats.child.stop(); stats //2 birds with one stone: remove the child + stop the other children
    } //TODO optimization to drop all children here already?
  }

  def processFailure(restart: Boolean, fail: Failed, children: Vector[ChildRestartStats]): Unit = {
    if (children.nonEmpty) {
      if (restart && children.forall(_.requestRestartPermission(retriesWindow)))
        children.foreach(_.child.restart(fail.cause))
      else
        children.foreach(_.child.stop())
    }
  }
}

object OneForOneStrategy {
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): OneForOneStrategy =
    new OneForOneStrategy(FaultHandlingStrategy.makeDecider(trapExit),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): OneForOneStrategy =
    new OneForOneStrategy(FaultHandlingStrategy.makeDecider(trapExit), maxNrOfRetries, withinTimeRange)
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Option[Int]): OneForOneStrategy =
    new OneForOneStrategy(FaultHandlingStrategy.makeDecider(trapExit), maxNrOfRetries, None)
}

/**
 * Restart an actor when it fails
 * trapExit = which Throwables should be intercepted
 * maxNrOfRetries = the number of times an actor is allowed to be restarted
 * withinTimeRange = millisecond time window for maxNrOfRetries, negative means no window
 */
case class OneForOneStrategy(decider: FaultHandlingStrategy.Decider,
                             maxNrOfRetries: Option[Int] = None,
                             withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy {

  def this(decider: FaultHandlingStrategy.JDecider, maxNrOfRetries: Int, withinTimeRange: Int) =
    this(FaultHandlingStrategy.makeDecider(decider),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries),
      if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: JIterable[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(FaultHandlingStrategy.makeDecider(trapExit),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries),
      if (withinTimeRange < 0) None else Some(withinTimeRange))

  def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    this(FaultHandlingStrategy.makeDecider(trapExit),
      if (maxNrOfRetries < 0) None else Some(maxNrOfRetries),
      if (withinTimeRange < 0) None else Some(withinTimeRange))

  /*
   *  this is a performance optimization to avoid re-allocating the pairs upon 
   *  every call to requestRestartPermission, assuming that strategies are shared
   *  across actors and thus this field does not take up much space
   */
  val retriesWindow = (maxNrOfRetries, withinTimeRange)

  def handleChildTerminated(child: ActorRef, children: Vector[ChildRestartStats]): Vector[ChildRestartStats] =
    children.filterNot(_.child == child) // TODO: check: I think this copies the whole vector in addition to allocating a closure ...

  def processFailure(restart: Boolean, fail: Failed, children: Vector[ChildRestartStats]): Unit = {
    children.find(_.child == fail.actor) match {
      case Some(stats) ⇒
        if (restart && stats.requestRestartPermission(retriesWindow))
          fail.actor.restart(fail.cause)
        else
          fail.actor.stop() //TODO optimization to drop child here already?
      case None ⇒ throw new AssertionError("Got Failure from non-child: " + fail)
    }
  }
}

