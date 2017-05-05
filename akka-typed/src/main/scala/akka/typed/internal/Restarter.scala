/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import java.util.concurrent.ThreadLocalRandom

import scala.annotation.tailrec
import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

import akka.actor.DeadLetterSuppression
import akka.annotation.InternalApi
import akka.event.Logging
import akka.typed.ActorContext
import akka.typed.Behavior
import akka.typed.Behavior.DeferredBehavior
import akka.typed.ExtensibleBehavior
import akka.typed.PreRestart
import akka.typed.Signal
import akka.typed.SupervisorStrategy._
import akka.typed.scaladsl.Actor._
import akka.util.OptionVal
import akka.typed.scaladsl.Actor

/**
 * INTERNAL API
 */
@InternalApi private[akka] object Restarter {
  def apply[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], strategy: SupervisorStrategy): Behavior[T] =
    Actor.deferred[T] { ctx ⇒
      val c = ctx.asInstanceOf[akka.typed.ActorContext[T]]
      val startedBehavior = initialUndefer(c, initialBehavior)
      strategy match {
        case Restart(-1, _, loggingEnabled) ⇒
          new Restarter(initialBehavior, startedBehavior, loggingEnabled)
        case r: Restart ⇒
          new LimitedRestarter(initialBehavior, startedBehavior, r, retries = 0, deadline = OptionVal.None)
        case Resume(loggingEnabled) ⇒ new Resumer(startedBehavior, loggingEnabled)
        case b: Backoff ⇒
          val backoffRestarter =
            new BackoffRestarter(
              initialBehavior.asInstanceOf[Behavior[Any]],
              startedBehavior.asInstanceOf[Behavior[Any]],
              b, restartCount = 0, blackhole = false)
          backoffRestarter.asInstanceOf[Behavior[T]]
      }
    }

  def initialUndefer[T](ctx: ActorContext[T], initialBehavior: Behavior[T]): Behavior[T] =
    Behavior.validateAsInitial(Behavior.undefer(initialBehavior, ctx))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] abstract class Supervisor[T, Thr <: Throwable: ClassTag] extends ExtensibleBehavior[T] {

  protected def loggingEnabled: Boolean

  /**
   * Current behavior
   */
  protected def behavior: Behavior[T]

  /**
   * Wrap next behavior in a concrete restarter again.
   */
  protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Supervisor[T, Thr]

  protected def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Supervisor[T, Thr]]

  protected def restart(ctx: ActorContext[T], initialBehavior: Behavior[T], startedBehavior: Behavior[T]): Supervisor[T, Thr] = {
    try Behavior.interpretSignal(startedBehavior, ctx, PreRestart) catch {
      case NonFatal(ex) ⇒ publish(ctx, Logging.Error(ex, ctx.asScala.self.path.toString, behavior.getClass,
        "failure during PreRestart"))
    }
    // no need to canonicalize, it's done in the calling methods
    wrap(Restarter.initialUndefer(ctx, initialBehavior), afterException = true)
  }

  @tailrec
  protected final def canonical(b: Behavior[T], ctx: ActorContext[T], afterException: Boolean): Behavior[T] =
    if (Behavior.isUnhandled(b)) Behavior.unhandled
    else if ((b eq Behavior.SameBehavior) || (b eq behavior)) Behavior.same
    else if (!Behavior.isAlive(b)) b
    else {
      b match {
        case d: DeferredBehavior[T] ⇒ canonical(Behavior.undefer(d, ctx), ctx, afterException)
        case b                      ⇒ wrap(b, afterException)
      }
    }

  override def receiveSignal(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    try {
      val b = Behavior.interpretSignal(behavior, ctx, signal)
      canonical(b, ctx, afterException = false)
    } catch handleException(ctx, behavior)
  }

  override def receiveMessage(ctx: ActorContext[T], msg: T): Behavior[T] = {
    try {
      val b = Behavior.interpretMessage(behavior, ctx, msg)
      canonical(b, ctx, afterException = false)
    } catch handleException(ctx, behavior)
  }

  protected def log(ctx: ActorContext[T], ex: Thr): Unit = {
    if (loggingEnabled)
      publish(ctx, Logging.Error(ex, ctx.asScala.self.toString, behavior.getClass, ex.getMessage))
  }

  protected final def publish(ctx: ActorContext[T], e: Logging.LogEvent): Unit =
    try ctx.asScala.system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Resumer[T, Thr <: Throwable: ClassTag](
  override val behavior: Behavior[T], override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Supervisor[T, Thr]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      wrap(startedBehavior, afterException = true)
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Supervisor[T, Thr] =
    new Resumer[T, Thr](nextBehavior, loggingEnabled)

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Restarter[T, Thr <: Throwable: ClassTag](
  initialBehavior: Behavior[T], override val behavior: Behavior[T],
  override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Supervisor[T, Thr]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      restart(ctx, initialBehavior, startedBehavior)
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Supervisor[T, Thr] =
    new Restarter[T, Thr](initialBehavior, nextBehavior, loggingEnabled)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class LimitedRestarter[T, Thr <: Throwable: ClassTag](
  initialBehavior: Behavior[T], override val behavior: Behavior[T],
  strategy: Restart, retries: Int, deadline: OptionVal[Deadline]) extends Supervisor[T, Thr] {

  override def loggingEnabled: Boolean = strategy.loggingEnabled

  private def deadlineHasTimeLeft: Boolean = deadline match {
    case OptionVal.None    ⇒ true
    case OptionVal.Some(d) ⇒ d.hasTimeLeft
  }

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Supervisor[T, Thr]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      if (deadlineHasTimeLeft && retries >= strategy.maxNrOfRetries)
        throw ex
      else
        restart(ctx, initialBehavior, startedBehavior)
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Supervisor[T, Thr] = {
    if (afterException) {
      val timeLeft = deadlineHasTimeLeft
      val newRetries = if (timeLeft) retries + 1 else 1
      val newDeadline = if (deadline.isDefined && timeLeft) deadline else OptionVal.Some(Deadline.now + strategy.withinTimeRange)
      new LimitedRestarter[T, Thr](initialBehavior, nextBehavior, strategy, newRetries, newDeadline)
    } else
      new LimitedRestarter[T, Thr](initialBehavior, nextBehavior, strategy, retries, deadline)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BackoffRestarter {
  /**
   * Calculates an exponential back off delay.
   */
  def calculateDelay(
    restartCount: Int,
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _                 ⇒ maxBackoff
      }
  }

  case object ScheduledRestart
  final case class ResetRestartCount(current: Int) extends DeadLetterSuppression
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class BackoffRestarter[T, Thr <: Throwable: ClassTag](
  initialBehavior: Behavior[Any], override val behavior: Behavior[Any],
  strategy: Backoff, restartCount: Int, blackhole: Boolean) extends Supervisor[Any, Thr] {

  // TODO using Any here because the scheduled messages can't be of type T.

  import BackoffRestarter._

  override def loggingEnabled: Boolean = strategy.loggingEnabled

  override def receiveSignal(ctx: ActorContext[Any], signal: Signal): Behavior[Any] = {
    if (blackhole) {
      ctx.asScala.system.eventStream.publish(Dropped(signal, ctx.asScala.self))
      Behavior.same
    } else
      super.receiveSignal(ctx, signal)
  }

  override def receiveMessage(ctx: ActorContext[Any], msg: Any): Behavior[Any] = {
    // intercept the scheduled messages and drop incoming messages if we are in backoff mode
    msg match {
      case ScheduledRestart ⇒
        // actual restart after scheduled backoff delay
        val restartedBehavior = Restarter.initialUndefer(ctx, initialBehavior)
        ctx.asScala.schedule(strategy.resetBackoffAfter, ctx.asScala.self, ResetRestartCount(restartCount))
        new BackoffRestarter[T, Thr](initialBehavior, restartedBehavior, strategy, restartCount, blackhole = false)
      case ResetRestartCount(current) ⇒
        if (current == restartCount)
          new BackoffRestarter[T, Thr](initialBehavior, behavior, strategy, restartCount = 0, blackhole)
        else
          Behavior.same
      case _ ⇒
        if (blackhole) {
          ctx.asScala.system.eventStream.publish(Dropped(msg, ctx.asScala.self))
          Behavior.same
        } else
          super.receiveMessage(ctx, msg)
    }
  }

  override def handleException(ctx: ActorContext[Any], startedBehavior: Behavior[Any]): Catcher[Supervisor[Any, Thr]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      // actual restart happens after the scheduled backoff delay
      try Behavior.interpretSignal(behavior, ctx, PreRestart) catch {
        case NonFatal(ex2) ⇒ publish(ctx, Logging.Error(ex2, ctx.asScala.self.path.toString, behavior.getClass,
          "failure during PreRestart"))
      }
      val restartDelay = calculateDelay(restartCount, strategy.minBackoff, strategy.maxBackoff, strategy.randomFactor)
      ctx.asScala.schedule(restartDelay, ctx.asScala.self, ScheduledRestart)
      new BackoffRestarter[T, Thr](initialBehavior, startedBehavior, strategy, restartCount + 1, blackhole = true)
  }

  override protected def wrap(nextBehavior: Behavior[Any], afterException: Boolean): Supervisor[Any, Thr] = {
    if (afterException)
      throw new IllegalStateException("wrap not expected afterException in BackoffRestarter")
    else
      new BackoffRestarter[T, Thr](initialBehavior, nextBehavior, strategy, restartCount, blackhole)
  }
}

