/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.util.concurrent.ThreadLocalRandom

import akka.actor.DeadLetterSuppression
import akka.actor.typed.BehaviorInterceptor.{ ReceiveTarget, SignalTarget }
import akka.actor.typed.SupervisorStrategy._
import akka.actor.typed.internal.BackoffRestarter.ScheduledRestart
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.util.{ OptionVal, PrettyDuration }

import scala.concurrent.duration.{ Deadline, FiniteDuration }
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal
import scala.concurrent.duration._

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
        case Stop(loggingEnabled) ⇒ new Stopper(initialBehavior, loggingEnabled)
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

abstract class AbstractSupervisor[O, I, Thr <: Throwable](ss: SupervisorStrategy)(implicit ev: ClassTag[Thr]) extends BehaviorInterceptor[O, I] {

  private val throwableClass = implicitly[ClassTag[Thr]].runtimeClass

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = {
    other match {
      case as: AbstractSupervisor[_, _, Thr] if throwableClass == as.throwableClass ⇒ true
      case _ ⇒ false
    }
  }

  override def preStart(ctx: ActorContext[O], target: BehaviorInterceptor.PreStartTarget[I]): Behavior[I] = {
    try {
      target.start(ctx)
    } catch handleExceptionOnStart(ctx)
  }

  def aroundSignal(ctx: ActorContext[O], signal: Signal, target: SignalTarget[I]): Behavior[I] = {
    try {
      target(ctx, signal)
    } catch handleSignalException(ctx, target)
  }

  def log(ctx: ActorContext[_], t: Throwable): Unit = {
    if (ss.loggingEnabled) {
      ctx.asScala.log.error(t, "Supervisor [{}] saw failure: {}", this, t.getMessage)
    }
  }

  protected def handleExceptionOnStart(ctx: ActorContext[O]): Catcher[Behavior[I]]
  protected def handleSignalException(ctx: ActorContext[O], target: BehaviorInterceptor.SignalTarget[I]): Catcher[Behavior[I]]
  protected def handleReceiveException(ctx: ActorContext[O], target: BehaviorInterceptor.ReceiveTarget[I]): Catcher[Behavior[I]]
}

/**
 * For cases where O == I for BehaviorInterceptor.
 */
abstract class SimpleSupervisor[T, Thr <: Throwable: ClassTag](ss: SupervisorStrategy) extends AbstractSupervisor[T, T, Thr](ss) {

  override def aroundReceive(ctx: ActorContext[T], msg: T, target: BehaviorInterceptor.ReceiveTarget[T]): Behavior[T] = {
    try {
      target(ctx, msg)
    } catch handleReceiveException(ctx, target)
  }

  protected def handleException(ctx: ActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(_: Thr) ⇒
      Behaviors.stopped
  }

  // convenience if target not required to handle exception
  protected def handleExceptionOnStart(ctx: ActorContext[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleSignalException(ctx: ActorContext[T], target: BehaviorInterceptor.SignalTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleReceiveException(ctx: ActorContext[T], target: BehaviorInterceptor.ReceiveTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
}

class StopSupervisor[T, Thr <: Throwable: ClassTag](initial: Behavior[T], strategy: Stop) extends SimpleSupervisor[T, Thr](strategy) {
  override def handleException(ctx: ActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      Behaviors.stopped
  }
}

class ResumeSupervisor[T, Thr <: Throwable: ClassTag](ss: Resume) extends SimpleSupervisor[T, Thr](ss) {
  override protected def handleException(ctx: ActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      Behaviors.same
  }
}

// FIXME tests + impl of resetting time
class RestartSupervisor[T, Thr <: Throwable](initial: Behavior[T], strategy: Restart)(implicit ev: ClassTag[Thr]) extends SimpleSupervisor[T, Thr](strategy) {

  var restarts = 0

  override def preStart(ctx: ActorContext[T], target: BehaviorInterceptor.PreStartTarget[T]): Behavior[T] = {
    try {
      target.start(ctx)
    } catch {
      case NonFatal(t: Thr) ⇒
        log(ctx, t)
        restarts += 1
        // if unlimited restarts then don't restart if starting fails as it would likely be an infinite restart loop
        if (restarts != strategy.maxNrOfRetries && strategy.maxNrOfRetries != -1) {
          preStart(ctx, target)
        } else {
          throw t
        }
    }
  }

  private def handleException(ctx: ActorContext[T], signalTarget: Signal ⇒ Behavior[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      restarts += 1
      signalTarget(PreRestart)
      if (restarts != strategy.maxNrOfRetries) {
        // TODO what about exceptions here?
        Behavior.validateAsInitial(Behavior.start(initial, ctx))
      } else {
        throw t
      }
  }


  override protected def handleSignalException(ctx: ActorContext[T], target: SignalTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, s ⇒ target(ctx, s))
  }
  override protected def handleReceiveException(ctx: ActorContext[T], target: BehaviorInterceptor.ReceiveTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, s ⇒ target.signal(ctx, s))
  }
}

class BackoffSupervisor[T, Thr <: Throwable: ClassTag](initial: Behavior[T], b: Backoff) extends AbstractSupervisor[AnyRef, T, Thr](b) {

  import BackoffRestarter._

  var blackhole = false
  var restartCount: Int = 0

  override def aroundReceive(ctx: ActorContext[AnyRef], msg: AnyRef, target: BehaviorInterceptor.ReceiveTarget[T]): Behavior[T] = {
    try {
      msg match {
        case ScheduledRestart ⇒
          blackhole = false
          // TODO do we need to start it?
          ctx.asScala.log.info("Scheduled restart")
          ctx.asScala.schedule(b.resetBackoffAfter, ctx.asScala.self, ResetRestartCount(restartCount))

          try {
            Behavior.validateAsInitial(Behavior.start(initial, ctx.asInstanceOf[ActorContext[T]]))
          } catch {
            case NonFatal(ex: Thr) ⇒
              val restartDelay = calculateDelay(restartCount, b.minBackoff, b.maxBackoff, b.randomFactor)
              ctx.asScala.log.info("Failure during initialisation")
              ctx.asScala.schedule(restartDelay, ctx.asScala.self, ScheduledRestart)
              restartCount += 1
              blackhole = true
              Behaviors.empty
          }
        case ResetRestartCount(current) ⇒
          println("Reset restart count: " + current)
          if (current == restartCount) {
            println("Resetting")
            restartCount = 0
          }
          Behavior.same
        case _ ⇒
          // TODO publish dropped message
          target(ctx, msg.asInstanceOf[T])
      }
    } catch handleReceiveException(ctx, target)
  }

  protected def handleExceptionOnStart(ctx: ActorContext[AnyRef]): Catcher[akka.actor.typed.Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      scheduleRestart(ctx)
  }

  protected def handleReceiveException(ctx: akka.actor.typed.ActorContext[AnyRef], target: BehaviorInterceptor.ReceiveTarget[T]): util.control.Exception.Catcher[akka.actor.typed.Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      target.signal(ctx, PreRestart)
      log(ctx, t)
      scheduleRestart(ctx)
  }

  protected def handleSignalException(ctx: ActorContext[AnyRef], target: BehaviorInterceptor.SignalTarget[T]): Catcher[akka.actor.typed.Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      target(ctx, PreRestart)
      log(ctx, t)
      scheduleRestart(ctx)
  }

  private def scheduleRestart(ctx: ActorContext[AnyRef]): Behavior[T] = {
    val restartDelay = calculateDelay(restartCount, b.minBackoff, b.maxBackoff, b.randomFactor)
    ctx.asScala.schedule(restartDelay, ctx.asScala.self, ScheduledRestart)
    restartCount += 1
    blackhole = true
    Behaviors.empty
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] abstract class Supervisor[T, Thr <: Throwable: ClassTag] extends ExtensibleBehavior[T] with WrappingBehavior[T, T] {

  private[akka] def throwableClass = implicitly[ClassTag[Thr]].runtimeClass

  protected def loggingEnabled: Boolean

  /**
   * Invoked when the actor is created (or re-created on restart) this is where a restarter implementation
   * can provide logic for dealing with exceptions thrown when running any actor initialization logic (undeferring).
   *
   * Note that the logic must take care to not wrap StoppedBehavior to avoid creating zombie behaviors that keep
   * running although stopped.
   *
   * @return The initial behavior of the actor after undeferring if needed
   */
  def init(ctx: ActorContext[T]): Behavior[T]

  /**
   * Current behavior
   */
  protected def behavior: Behavior[T]

  def nestedBehavior: Behavior[T] = behavior

  def replaceNested(newNested: Behavior[T]): Behavior[T] = wrap(newNested, afterException = false)

  /**
   * Wrap next behavior in a concrete restarter again.
   */
  protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Behavior[T]

  protected def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Behavior[T]]

  protected def restart(ctx: ActorContext[T], initialBehavior: Behavior[T], startedBehavior: Behavior[T]): Behavior[T] = {
    try Behavior.interpretSignal(startedBehavior, ctx, PreRestart) catch {
      case NonFatal(ex) ⇒ ctx.asScala.log.error(ex, "failure during PreRestart")
    }
    wrap(initialBehavior, afterException = true) match {
      case s: Supervisor[T, Thr] ⇒ s.init(ctx)
      case b                     ⇒ b
    }
  }

  protected final def supervise(nextBehavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
    val started = Behavior.start(nextBehavior, ctx)

    val throwableAlreadyHandled = Behavior.existsInStack(started) {
      case s: Supervisor[T, Thr] if s.throwableClass == throwableClass ⇒ true
      case _ ⇒ false
    }
    if (throwableAlreadyHandled) started
    else Behavior.wrap[T, T](behavior, started, ctx)(wrap(_, afterException = false))
  }

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
      ctx.asScala.log.error(ex, "Supervisor [{}] saw failure: {}", this, ex.getMessage)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Stopper[T, Thr <: Throwable: ClassTag](
  override val behavior: Behavior[T], override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  def init(ctx: ActorContext[T]): Behavior[T] = {
    try {
      val started = Behavior.validateAsInitial(Behavior.start(behavior, ctx))
      if (Behavior.isAlive(started)) wrap(started, false)
      else started
    } catch {
      case NonFatal(ex: Thr) ⇒
        log(ctx, ex)
        Behavior.stopped
    }
  }

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Behavior[T]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      Behaviors.stopped
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Behavior[T] =
    new Stopper[T, Thr](nextBehavior, loggingEnabled)

  override def toString = "stop"

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class Restarter[T, Thr <: Throwable: ClassTag](
  initialBehavior: Behavior[T], override val behavior: Behavior[T],
  override val loggingEnabled: Boolean) extends Supervisor[T, Thr] {

  override def init(ctx: ActorContext[T]) = {
    // no handling of errors for Restart as that could lead to infinite restart-loop
    val started = Behavior.validateAsInitial(Behavior.start(behavior, ctx))
    if (Behavior.isAlive(started)) wrap(started, afterException = false)
    else started
  }

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Behavior[T]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      restart(ctx, initialBehavior, startedBehavior)
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Behavior[T] =
    new Restarter[T, Thr](initialBehavior, nextBehavior, loggingEnabled)

  override def toString = "restart"
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
      val started = Behavior.validateAsInitial(Behavior.start(behavior, ctx))
      if (Behavior.isAlive(started)) wrap(started, afterException = false)
      else started
    } catch {
      case NonFatal(ex: Thr) ⇒
        log(ctx, ex)
        // we haven't actually wrapped and increased retries yet, so need to compare with +1
        if (deadlineHasTimeLeft && (retries + 1) >= strategy.maxNrOfRetries) throw ex
        else {
          wrap(initialBehavior, afterException = true) match {
            case s: Supervisor[T, Thr] ⇒ s.init(ctx)
            case b                     ⇒ b
          }
        }
    }

  private def deadlineHasTimeLeft: Boolean = deadline match {
    case OptionVal.None    ⇒ true
    case OptionVal.Some(d) ⇒ d.hasTimeLeft
  }

  override def handleException(ctx: ActorContext[T], startedBehavior: Behavior[T]): Catcher[Behavior[T]] = {
    case NonFatal(ex: Thr) ⇒
      log(ctx, ex)
      if (deadlineHasTimeLeft && retries >= strategy.maxNrOfRetries)
        throw ex
      else
        restart(ctx, initialBehavior, startedBehavior)
  }

  override protected def wrap(nextBehavior: Behavior[T], afterException: Boolean): Behavior[T] = {
    val restarter = if (afterException) {
      val timeLeft = deadlineHasTimeLeft
      val newRetries = if (timeLeft) retries + 1 else 1
      val newDeadline = if (deadline.isDefined && timeLeft) deadline else OptionVal.Some(Deadline.now + strategy.withinTimeRange)
      new LimitedRestarter[T, Thr](initialBehavior, nextBehavior, strategy, newRetries, newDeadline)
    } else
      new LimitedRestarter[T, Thr](initialBehavior, nextBehavior, strategy, retries, deadline)

    restarter
  }

  override def toString = s"restartWithLimit(${strategy.maxNrOfRetries}, ${PrettyDuration.format(strategy.withinTimeRange)})"
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

  // FIXME weird hack here to avoid having any Behavior.start call start the supervised behavior early when we are backing off
  // maybe we can solve this in a less strange way?
  override def nestedBehavior: Behavior[Any] =
    if (blackhole) {
      // if we are currently backing off, we don't want someone outside to start any deferred behaviors
      Behaviors.empty
    } else {
      behavior
    }

  override def replaceNested(newNested: Behavior[Any]): Behavior[Any] = {
    if (blackhole) {
      // if we are currently backing off, we don't want someone outside to replace our inner behavior
      this
    } else {
      super.replaceNested(newNested)
    }
  }

  def init(ctx: ActorContext[Any]) =
    try {
      val started = Behavior.validateAsInitial(Behavior.start(initialBehavior, ctx))
      if (Behavior.isAlive(started)) wrap(started, afterException = false)
      else started
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

  override protected def wrap(nextBehavior: Behavior[Any], afterException: Boolean): Behavior[Any] = {
    if (afterException)
      throw new IllegalStateException("wrap not expected afterException in BackoffRestarter")
    else
      new BackoffRestarter[T, Thr](initialBehavior, nextBehavior, strategy, restartCount, blackhole)
  }

  override def toString = s"restartWithBackoff(${PrettyDuration.format(strategy.minBackoff)}, ${PrettyDuration.format(strategy.maxBackoff)}, ${strategy.randomFactor})"
}

