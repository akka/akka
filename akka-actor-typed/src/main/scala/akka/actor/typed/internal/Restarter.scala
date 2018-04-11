/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.util.concurrent.ThreadLocalRandom

import akka.actor.DeadLetterSuppression
import akka.actor.typed.SupervisorStrategy._
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.util.OptionVal

import scala.concurrent.duration.{ Deadline, FiniteDuration }
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object Supervisor {
  def apply[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], strategy: SupervisorStrategy): Behavior[T] =
    Behaviors.setup[T] { ctx ⇒
      val supervisor: Supervisor[T, Thr] = strategy match {
        case Restart(-1, _, loggingEnabled) ⇒
          new Restarter(initialBehavior, initialBehavior, loggingEnabled)
        case r: Restart ⇒
          new LimitedRestarter(initialBehavior, initialBehavior, r, retries = 0, deadline = OptionVal.None)
        case Resume(loggingEnabled) ⇒ new Resumer(initialBehavior, loggingEnabled)
        case Stop(loggingEnabled)   ⇒ new Stopper(initialBehavior, loggingEnabled)
        case b: Backoff ⇒
          val backoffRestarter =
            new BackoffRestarter(
              initialBehavior.asInstanceOf[Behavior[Any]],
              initialBehavior.asInstanceOf[Behavior[Any]],
              b, restartCount = 0, blackhole = false)
          backoffRestarter
            .asInstanceOf[Supervisor[T, Thr]]
      }

      supervisor.init(ctx)
    }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] abstract class Supervisor[T, Thr <: Throwable: ClassTag] extends ExtensibleBehavior[T] {

  protected def loggingEnabled: Boolean

  /**
   * Invoked when the actor is created (or re-created on restart) this is where a restarter implementation
   * can provide logic for dealing with exceptions thrown when running any actor initialization logic (undeferring).
   *
   * @return The initial behavior of the actor after undeferring if needed
   */
  def init(ctx: ActorContext[T]): Supervisor[T, Thr]

  /**
   * Current behavior
   */
  protected def behavior: Behavior[T]

  /**
   * Wrap next behavior in a concrete restarter again.
   */
  protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Supervisor[T, Thr]

  protected def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Behavior[T]]

  protected def restart(ctx: ActorContext[T], initialBehavior: Behavior[T], startedBehavior: Behavior[T]): Supervisor[T, Thr] = {
    try Behavior.interpretSignal(startedBehavior, ctx, PreRestart) catch {
      case NonFatal(ex) ⇒ ctx.asScala.log.error(ex, "failure during PreRestart")
    }
    wrap(initialBehavior, afterException = true).init(ctx)
  }

  protected final def supervise(nextBehavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] =
    Behavior.wrap[T, T](behavior, nextBehavior, ctx)(wrap(_, afterException = false))

  override def receiveSignal(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    try {
      val b = Behavior.interpretSignal(behavior, ctx, signal)
      supervise(b, ctx)
    } catch handleException(ctx, behavior)
  }

  override def receive(ctx: ActorContext[T], msg: T): Behavior[T] = {
    try {
      val b = Behavior.interpretMessage(behavior, ctx, msg)
      supervise(b, ctx)
    } catch handleException(ctx, behavior)
  }

  protected def log(ctx: ActorContext[T], ex: Thr): Unit = {
    if (loggingEnabled)
      ctx.asScala.log.error(ex, ex.getMessage)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Resumer[T, Thr <: Throwable: ClassTag](
  override val behavior: Behavior[T], override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  def init(ctx: ActorContext[T]) =
    // no handling of errors for Resume as that could lead to infinite restart-loop
    wrap(Behavior.validateAsInitial(Behavior.start(behavior, ctx)), afterException = false)

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
@InternalApi private[akka] final class Stopper[T, Thr <: Throwable: ClassTag](
  override val behavior: Behavior[T], override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  def init(ctx: ActorContext[T]): Supervisor[T, Thr] =
    wrap(Behavior.validateAsInitial(Behavior.start(behavior, ctx)), false)

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Behavior[T]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      Behaviors.stopped
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Supervisor[T, Thr] =
    new Stopper[T, Thr](nextBehavior, loggingEnabled)

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Restarter[T, Thr <: Throwable: ClassTag](
  initialBehavior: Behavior[T], override val behavior: Behavior[T],
  override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  override def init(ctx: ActorContext[T]) =
    // no handling of errors for Restart as that could lead to infinite restart-loop
    wrap(Behavior.validateAsInitial(Behavior.start(behavior, ctx)), afterException = false)

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

  override def init(ctx: ActorContext[T]) =
    try {
      wrap(Behavior.validateAsInitial(Behavior.start(behavior, ctx)), afterException = false)
    } catch {
      case NonFatal(ex: Thr) ⇒
        log(ctx, ex)
        // we haven't actually wrapped and increased retries yet, so need to compare with +1
        if (deadlineHasTimeLeft && (retries + 1) >= strategy.maxNrOfRetries) throw ex
        else wrap(initialBehavior, afterException = true).init(ctx)
    }

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

  def init(ctx: ActorContext[Any]): Supervisor[Any, Thr] =
    try {
      val startedBehavior = Behavior.validateAsInitial(Behavior.start(initialBehavior, ctx))
      new BackoffRestarter(initialBehavior, startedBehavior, strategy, restartCount, blackhole)
    } catch {
      case NonFatal(ex: Thr) ⇒
        log(ctx, ex)
        val restartDelay = calculateDelay(restartCount, strategy.minBackoff, strategy.maxBackoff, strategy.randomFactor)
        ctx.asScala.schedule(restartDelay, ctx.asScala.self, ScheduledRestart)
        new BackoffRestarter[T, Thr](initialBehavior, initialBehavior, strategy, restartCount + 1, blackhole = true)
    }

  override def receiveSignal(ctx: ActorContext[Any], signal: Signal): Behavior[Any] = {
    if (blackhole) {
      import scaladsl.adapter._
      ctx.asScala.system.toUntyped.eventStream.publish(Dropped(signal, ctx.asScala.self))
      Behavior.same
    } else
      super.receiveSignal(ctx, signal)
  }

  override def receive(ctx: ActorContext[Any], msg: Any): Behavior[Any] = {
    // intercept the scheduled messages and drop incoming messages if we are in backoff mode
    msg match {
      case ScheduledRestart ⇒
        // actual restart after scheduled backoff delay
        ctx.asScala.schedule(strategy.resetBackoffAfter, ctx.asScala.self, ResetRestartCount(restartCount))
        new BackoffRestarter[T, Thr](initialBehavior, initialBehavior, strategy, restartCount, blackhole = false).init(ctx)
      case ResetRestartCount(current) ⇒
        if (current == restartCount)
          new BackoffRestarter[T, Thr](initialBehavior, behavior, strategy, restartCount = 0, blackhole)
        else
          Behavior.same
      case _ ⇒
        if (blackhole) {
          import scaladsl.adapter._
          ctx.asScala.system.toUntyped.eventStream.publish(Dropped(msg, ctx.asScala.self))
          Behavior.same
        } else
          super.receive(ctx, msg)
    }
  }

  override def handleException(ctx: ActorContext[Any], startedBehavior: Behavior[Any]): Catcher[Supervisor[Any, Thr]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      // actual restart happens after the scheduled backoff delay
      try Behavior.interpretSignal(behavior, ctx, PreRestart) catch {
        case NonFatal(ex2) ⇒ ctx.asScala.log.error(ex2, "failure during PreRestart")
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

