/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import java.lang.{ Iterable ⇒ JIterable }
import akka.util.Duration

/**
 * ChildRestartStats is the statistics kept by every parent Actor for every child Actor
 * and is used for SupervisorStrategies to know how to deal with problems that occur for the children.
 */
case class ChildRestartStats(val child: ActorRef, var maxNrOfRetriesCount: Int = 0, var restartTimeWindowStartNanos: Long = 0L) {

  //FIXME How about making ChildRestartStats immutable and then move these methods into the actual supervisor strategies?
  def requestRestartPermission(retriesWindow: (Option[Int], Option[Int])): Boolean =
    retriesWindow match {
      case (Some(retries), _) if retries < 1 ⇒ false
      case (Some(retries), None)             ⇒ maxNrOfRetriesCount += 1; maxNrOfRetriesCount <= retries
      case (x, Some(window))                 ⇒ retriesInWindowOkay(if (x.isDefined) x.get else 1, window)
      case (None, _)                         ⇒ true
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

trait SupervisorStrategyLowPriorityImplicits { this: SupervisorStrategy.type ⇒

  /**
   * Implicit conversion from `Seq` of Cause-Directive pairs to a `Decider`. See makeDecider(causeDirective).
   */
  implicit def seqCauseDirective2Decider(trapExit: Iterable[CauseDirective]): Decider = makeDecider(trapExit)
  // the above would clash with seqThrowable2Decider for empty lists
}

object SupervisorStrategy extends SupervisorStrategyLowPriorityImplicits {
  sealed trait Directive

  /**
   * Resumes message processing for the failed Actor
   */
  case object Resume extends Directive

  /**
   * Discards the old Actor instance and replaces it with a new,
   * then resumes message processing.
   */
  case object Restart extends Directive

  /**
   * Stops the Actor
   */
  case object Stop extends Directive

  /**
   * Escalates the failure to the supervisor of the supervisor,
   * by rethrowing the cause of the failure.
   */
  case object Escalate extends Directive

  /**
   * Resumes message processing for the failed Actor
   * Java API
   */
  def resume = Resume

  /**
   * Discards the old Actor instance and replaces it with a new,
   * then resumes message processing.
   * Java API
   */
  def restart = Restart

  /**
   * Stops the Actor
   * Java API
   */
  def stop = Stop

  /**
   * Escalates the failure to the supervisor of the supervisor,
   * by rethrowing the cause of the failure.
   * Java API
   */
  def escalate = Escalate

  /**
   * When supervisorStrategy is not specified for an actor this
   * is used by default. The child will be stopped when
   * [[akka.ActorInitializationException]] or [[akka.ActorKilledException]]
   * is thrown. It will be restarted for other `Exception` types.
   * The error is escalated if it's a `Throwable`, i.e. `Error`.
   */
  final val defaultStrategy: SupervisorStrategy = {
    def defaultDecider: Decider = {
      case _: ActorInitializationException ⇒ Stop
      case _: ActorKilledException         ⇒ Stop
      case _: Exception                    ⇒ Restart
      case _                               ⇒ Escalate
    }
    OneForOneStrategy()(defaultDecider)
  }

  /**
   * Implicit conversion from `Seq` of Throwables to a `Decider`.
   * This maps the given Throwables to restarts, otherwise escalates.
   */
  implicit def seqThrowable2Decider(trapExit: Seq[Class[_ <: Throwable]]): Decider = makeDecider(trapExit)

  type Decider = PartialFunction[Throwable, Directive]
  type JDecider = akka.japi.Function[Throwable, Directive]
  type CauseDirective = (Class[_ <: Throwable], Directive)

  /**
   * Decider builder which just checks whether one of
   * the given Throwables matches the cause and restarts, otherwise escalates.
   */
  def makeDecider(trapExit: Array[Class[_]]): Decider =
    { case x ⇒ if (trapExit exists (_ isInstance x)) Restart else Escalate }

  /**
   * Decider builder which just checks whether one of
   * the given Throwables matches the cause and restarts, otherwise escalates.
   */
  def makeDecider(trapExit: Seq[Class[_ <: Throwable]]): Decider =
    { case x ⇒ if (trapExit exists (_ isInstance x)) Restart else Escalate }

  /**
   * Decider builder which just checks whether one of
   * the given Throwables matches the cause and restarts, otherwise escalates.
   */
  def makeDecider(trapExit: JIterable[Class[_ <: Throwable]]): Decider = makeDecider(trapExit.toSeq)

  /**
   * Decider builder for Iterables of cause-directive pairs, e.g. a map obtained
   * from configuration; will sort the pairs so that the most specific type is
   * checked before all its subtypes, allowing carving out subtrees of the
   * Throwable hierarchy.
   */
  def makeDecider(flat: Iterable[CauseDirective]): Decider = {
    val directives = sort(flat)

    { case x ⇒ directives find (_._1 isInstance x) map (_._2) getOrElse Escalate }
  }

  /**
   * Converts a Java Decider into a Scala Decider
   */
  def makeDecider(func: JDecider): Decider = { case x ⇒ func(x) }

  /**
   * Sort so that subtypes always precede their supertypes, but without
   * obeying any order between unrelated subtypes (insert sort).
   *
   * INTERNAL API
   */
  private[akka] def sort(in: Iterable[CauseDirective]): Seq[CauseDirective] =
    (new ArrayBuffer[CauseDirective](in.size) /: in) { (buf, ca) ⇒
      buf.indexWhere(_._1 isAssignableFrom ca._1) match {
        case -1 ⇒ buf append ca
        case x  ⇒ buf insert (x, ca)
      }
      buf
    }

  private[akka] def withinTimeRangeOption(withinTimeRange: Duration): Option[Duration] =
    if (withinTimeRange.isFinite && withinTimeRange >= Duration.Zero) Some(withinTimeRange) else None

  private[akka] def maxNrOfRetriesOption(maxNrOfRetries: Int): Option[Int] =
    if (maxNrOfRetries < 0) None else Some(maxNrOfRetries)
}

/**
 * An Akka SupervisorStrategy is the policy to apply for crashing children
 */
abstract class SupervisorStrategy {

  import SupervisorStrategy._

  /**
   * Returns the Decider that is associated with this SupervisorStrategy
   */
  def decider: Decider

  /**
   * This method is called after the child has been removed from the set of children.
   */
  def handleChildTerminated(context: ActorContext, child: ActorRef, children: Iterable[ActorRef]): Unit

  /**
   * This method is called to act on the failure of a child: restart if the flag is true, stop otherwise.
   */
  def processFailure(context: ActorContext, restart: Boolean, child: ActorRef, cause: Throwable, stats: ChildRestartStats, children: Iterable[ChildRestartStats]): Unit

  //FIXME docs
  def handleSupervisorFailing(supervisor: ActorRef, children: Iterable[ActorRef]): Unit =
    if (children.nonEmpty) children.foreach(_.asInstanceOf[InternalActorRef].suspend())

  //FIXME docs
  def handleSupervisorRestarted(cause: Throwable, supervisor: ActorRef, children: Iterable[ActorRef]): Unit =
    if (children.nonEmpty) children.foreach(_.asInstanceOf[InternalActorRef].restart(cause))

  /**
   * Returns whether it processed the failure or not
   */
  def handleFailure(context: ActorContext, child: ActorRef, cause: Throwable, stats: ChildRestartStats, children: Iterable[ChildRestartStats]): Boolean = {
    val directive = if (decider.isDefinedAt(cause)) decider(cause) else Escalate //FIXME applyOrElse in Scala 2.10
    directive match {
      case Resume   ⇒ child.asInstanceOf[InternalActorRef].resume(); true
      case Restart  ⇒ processFailure(context, true, child, cause, stats, children); true
      case Stop     ⇒ processFailure(context, false, child, cause, stats, children); true
      case Escalate ⇒ false
    }
  }

}

/**
 * Applies the fault handling `Directive` (Resume, Restart, Stop) specified in the `Decider`
 * to all children when one fails, as opposed to [[akka.actor.OneForOneStrategy]] that applies
 * it only to the child actor that failed.
 *
 * @param maxNrOfRetries the number of times an actor is allowed to be restarted, negative value means no limit
 * @param withinTimeRange duration of the time window for maxNrOfRetries, Duration.Inf means no window
 * @param decider mapping from Throwable to [[akka.actor.SupervisorStrategy.Directive]], you can also use a
 *   `Seq` of Throwables which maps the given Throwables to restarts, otherwise escalates.
 */
case class AllForOneStrategy(maxNrOfRetries: Int = -1, withinTimeRange: Duration = Duration.Inf)(val decider: SupervisorStrategy.Decider)
  extends SupervisorStrategy {

  import SupervisorStrategy._

  def this(maxNrOfRetries: Int, withinTimeRange: Duration, decider: SupervisorStrategy.JDecider) =
    this(maxNrOfRetries, withinTimeRange)(SupervisorStrategy.makeDecider(decider))

  def this(maxNrOfRetries: Int, withinTimeRange: Duration, trapExit: JIterable[Class[_ <: Throwable]]) =
    this(maxNrOfRetries, withinTimeRange)(SupervisorStrategy.makeDecider(trapExit))

  def this(maxNrOfRetries: Int, withinTimeRange: Duration, trapExit: Array[Class[_]]) =
    this(maxNrOfRetries, withinTimeRange)(SupervisorStrategy.makeDecider(trapExit))

  /*
   *  this is a performance optimization to avoid re-allocating the pairs upon
   *  every call to requestRestartPermission, assuming that strategies are shared
   *  across actors and thus this field does not take up much space
   */
  private val retriesWindow = (maxNrOfRetriesOption(maxNrOfRetries), withinTimeRangeOption(withinTimeRange).map(_.toMillis.toInt))

  def handleChildTerminated(context: ActorContext, child: ActorRef, children: Iterable[ActorRef]): Unit = {}

  def processFailure(context: ActorContext, restart: Boolean, child: ActorRef, cause: Throwable, stats: ChildRestartStats, children: Iterable[ChildRestartStats]): Unit = {
    if (children.nonEmpty) {
      if (restart && children.forall(_.requestRestartPermission(retriesWindow)))
        children.foreach(_.child.asInstanceOf[InternalActorRef].restart(cause))
      else
        for (c ← children) context.stop(c.child)
    }
  }
}

/**
 * Applies the fault handling `Directive` (Resume, Restart, Stop) specified in the `Decider`
 * to the child actor that failed, as opposed to [[akka.actor.AllForOneStrategy]] that applies
 * it to all children.
 *
 * @param maxNrOfRetries the number of times an actor is allowed to be restarted, negative value means no limit
 * @param withinTimeRange duration of the time window for maxNrOfRetries, Duration.Inf means no window
 * @param decider mapping from Throwable to [[akka.actor.SupervisorStrategy.Directive]], you can also use a
 *   `Seq` of Throwables which maps the given Throwables to restarts, otherwise escalates.
 */
case class OneForOneStrategy(maxNrOfRetries: Int = -1, withinTimeRange: Duration = Duration.Inf)(val decider: SupervisorStrategy.Decider)
  extends SupervisorStrategy {

  def this(maxNrOfRetries: Int, withinTimeRange: Duration, decider: SupervisorStrategy.JDecider) =
    this(maxNrOfRetries, withinTimeRange)(SupervisorStrategy.makeDecider(decider))

  def this(maxNrOfRetries: Int, withinTimeRange: Duration, trapExit: JIterable[Class[_ <: Throwable]]) =
    this(maxNrOfRetries, withinTimeRange)(SupervisorStrategy.makeDecider(trapExit))

  def this(maxNrOfRetries: Int, withinTimeRange: Duration, trapExit: Array[Class[_]]) =
    this(maxNrOfRetries, withinTimeRange)(SupervisorStrategy.makeDecider(trapExit))

  /*
   *  this is a performance optimization to avoid re-allocating the pairs upon
   *  every call to requestRestartPermission, assuming that strategies are shared
   *  across actors and thus this field does not take up much space
   */
  private val retriesWindow = (
    SupervisorStrategy.maxNrOfRetriesOption(maxNrOfRetries),
    SupervisorStrategy.withinTimeRangeOption(withinTimeRange).map(_.toMillis.toInt))

  def handleChildTerminated(context: ActorContext, child: ActorRef, children: Iterable[ActorRef]): Unit = {}

  def processFailure(context: ActorContext, restart: Boolean, child: ActorRef, cause: Throwable, stats: ChildRestartStats, children: Iterable[ChildRestartStats]): Unit = {
    if (restart && stats.requestRestartPermission(retriesWindow))
      child.asInstanceOf[InternalActorRef].restart(cause)
    else
      context.stop(child) //TODO optimization to drop child here already?
  }
}

