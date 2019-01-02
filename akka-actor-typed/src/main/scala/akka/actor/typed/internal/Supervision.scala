/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.util.concurrent.ThreadLocalRandom

import akka.actor.DeadLetterSuppression
import akka.actor.typed.BehaviorInterceptor.{ PreStartTarget, ReceiveTarget, SignalTarget }
import akka.actor.typed.SupervisorStrategy._
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.util.{ OptionVal, unused }

import scala.concurrent.duration.{ Deadline, FiniteDuration }
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object Supervisor {
  def apply[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], strategy: SupervisorStrategy): Behavior[T] = {
    strategy match {
      case r: Resume ⇒
        Behaviors.intercept[T, T](new ResumeSupervisor(r))(initialBehavior)
      case r: Restart ⇒
        Behaviors.intercept[T, T](new RestartSupervisor(initialBehavior, r))(initialBehavior)
      case r: Stop ⇒
        Behaviors.intercept[T, T](new StopSupervisor(r))(initialBehavior)
      case r: Backoff ⇒
        Behaviors.intercept[T, T](new BackoffSupervisor(initialBehavior, r))(initialBehavior)
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private abstract class AbstractSupervisor[O, I, Thr <: Throwable](strategy: SupervisorStrategy)(implicit ev: ClassTag[Thr]) extends BehaviorInterceptor[O, I] {

  private val throwableClass = implicitly[ClassTag[Thr]].runtimeClass

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = {
    other match {
      case as: AbstractSupervisor[_, _, Thr] if throwableClass == as.throwableClass ⇒ true
      case _ ⇒ false
    }
  }

  override def aroundStart(ctx: TypedActorContext[O], target: PreStartTarget[I]): Behavior[I] = {
    try {
      target.start(ctx)
    } catch handleExceptionOnStart(ctx)
  }

  def aroundSignal(ctx: TypedActorContext[O], signal: Signal, target: SignalTarget[I]): Behavior[I] = {
    try {
      target(ctx, signal)
    } catch handleSignalException(ctx, target)
  }

  def log(ctx: TypedActorContext[_], t: Throwable): Unit = {
    if (strategy.loggingEnabled) {
      ctx.asScala.log.error(t, "Supervisor {} saw failure: {}", this, t.getMessage)
    }
  }

  protected def handleExceptionOnStart(ctx: TypedActorContext[O]): Catcher[Behavior[I]]
  protected def handleSignalException(ctx: TypedActorContext[O], target: SignalTarget[I]): Catcher[Behavior[I]]
  protected def handleReceiveException(ctx: TypedActorContext[O], target: ReceiveTarget[I]): Catcher[Behavior[I]]
}

/**
 * For cases where O == I for BehaviorInterceptor.
 */
private abstract class SimpleSupervisor[T, Thr <: Throwable: ClassTag](ss: SupervisorStrategy) extends AbstractSupervisor[T, T, Thr](ss) {

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    try {
      target(ctx, msg)
    } catch handleReceiveException(ctx, target)
  }

  protected def handleException(@unused ctx: TypedActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      Behavior.failed(t)
  }

  // convenience if target not required to handle exception
  protected def handleExceptionOnStart(ctx: TypedActorContext[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleSignalException(ctx: TypedActorContext[T], target: SignalTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleReceiveException(ctx: TypedActorContext[T], target: ReceiveTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
}

private class StopSupervisor[T, Thr <: Throwable: ClassTag](strategy: Stop) extends SimpleSupervisor[T, Thr](strategy) {
  override def handleException(ctx: TypedActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      Behavior.failed(t)
  }
}

private class ResumeSupervisor[T, Thr <: Throwable: ClassTag](ss: Resume) extends SimpleSupervisor[T, Thr](ss) {
  override protected def handleException(ctx: TypedActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      Behaviors.same
  }
}

private class RestartSupervisor[T, Thr <: Throwable](initial: Behavior[T], strategy: Restart)(implicit ev: ClassTag[Thr]) extends SimpleSupervisor[T, Thr](strategy) {

  private var restarts = 0
  private var deadline: OptionVal[Deadline] = OptionVal.None

  private def deadlineHasTimeLeft: Boolean = deadline match {
    case OptionVal.None    ⇒ true
    case OptionVal.Some(d) ⇒ d.hasTimeLeft
  }

  override def aroundStart(ctx: TypedActorContext[T], target: PreStartTarget[T]): Behavior[T] = {
    try {
      target.start(ctx)
    } catch {
      case NonFatal(t: Thr) ⇒
        // if unlimited restarts then don't restart if starting fails as it would likely be an infinite restart loop
        if (strategy.unlimitedRestarts() || ((restarts + 1) >= strategy.maxNrOfRetries && deadlineHasTimeLeft)) {
          // don't log here as it'll be logged as ActorInitializationException
          throw t
        } else {
          log(ctx, t)
          restart()
          aroundStart(ctx, target)
        }
    }
  }

  private def restart() = {
    val timeLeft = deadlineHasTimeLeft
    val newDeadline = if (deadline.isDefined && timeLeft) deadline else OptionVal.Some(Deadline.now + strategy.withinTimeRange)
    restarts = if (timeLeft) restarts + 1 else 1
    deadline = newDeadline
  }

  private def handleException(ctx: TypedActorContext[T], signalRestart: () ⇒ Unit): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      if (strategy.maxNrOfRetries != -1 && restarts >= strategy.maxNrOfRetries && deadlineHasTimeLeft) {
        throw t
      } else {
        try {
          signalRestart()
        } catch {
          case NonFatal(ex) ⇒ ctx.asScala.log.error(ex, "failure during PreRestart")
        }
        log(ctx, t)
        restart()
        Behavior.validateAsInitial(Behavior.start(initial, ctx))
      }
  }

  override protected def handleSignalException(ctx: TypedActorContext[T], target: SignalTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, () ⇒ target(ctx, PreRestart))
  }
  override protected def handleReceiveException(ctx: TypedActorContext[T], target: ReceiveTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, () ⇒ target.signalRestart(ctx))
  }
}

private class BackoffSupervisor[O, T, Thr <: Throwable: ClassTag](initial: Behavior[T], b: Backoff) extends AbstractSupervisor[O, T, Thr](b) {

  import BackoffSupervisor._

  var blackhole = false
  var restartCount: Int = 0

  override def aroundSignal(ctx: TypedActorContext[O], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    if (blackhole) {
      import akka.actor.typed.scaladsl.adapter._
      ctx.asScala.system.toUntyped.eventStream.publish(Dropped(signal, ctx.asScala.self))
      Behaviors.same
    } else {
      super.aroundSignal(ctx, signal, target)
    }
  }

  override def aroundReceive(ctx: TypedActorContext[O], msg: O, target: ReceiveTarget[T]): Behavior[T] = {
    try {
      msg.asInstanceOf[Any] match {
        case ScheduledRestart ⇒
          blackhole = false
          ctx.asScala.scheduleOnce(b.resetBackoffAfter, ctx.asScala.self.unsafeUpcast[Any], ResetRestartCount(restartCount))
          try {
            Behavior.validateAsInitial(Behavior.start(initial, ctx.asInstanceOf[TypedActorContext[T]]))
          } catch {
            case NonFatal(ex: Thr) if b.maxRestarts > 0 && restartCount >= b.maxRestarts ⇒
              log(ctx, ex)
              Behavior.failed(ex)
            case NonFatal(ex: Thr) ⇒ scheduleRestart(ctx, ex)
          }
        case ResetRestartCount(current) ⇒
          if (current == restartCount) {
            restartCount = 0
          }
          Behavior.same
        case _ ⇒
          if (blackhole) {
            import akka.actor.typed.scaladsl.adapter._
            ctx.asScala.system.toUntyped.eventStream.publish(Dropped(msg, ctx.asScala.self.unsafeUpcast[Any]))
            Behaviors.same
          } else {
            target(ctx, msg.asInstanceOf[T])
          }
      }
    } catch handleReceiveException(ctx, target)
  }

  protected def handleExceptionOnStart(ctx: TypedActorContext[O]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      scheduleRestart(ctx, t)
  }

  protected def handleReceiveException(ctx: TypedActorContext[O], target: ReceiveTarget[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      try {
        target.signalRestart(ctx)
      } catch {
        case NonFatal(ex) ⇒ ctx.asScala.log.error(ex, "failure during PreRestart")
      }
      scheduleRestart(ctx, t)
  }

  protected def handleSignalException(ctx: TypedActorContext[O], target: SignalTarget[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      try {
        target(ctx, PreRestart)
      } catch {
        case NonFatal(ex) ⇒ ctx.asScala.log.error(ex, "failure during PreRestart")
      }
      scheduleRestart(ctx, t)
  }

  private def scheduleRestart(ctx: TypedActorContext[O], reason: Throwable): Behavior[T] = {
    log(ctx, reason)
    val restartDelay = calculateDelay(restartCount, b.minBackoff, b.maxBackoff, b.randomFactor)
    ctx.asScala.scheduleOnce(restartDelay, ctx.asScala.self.unsafeUpcast[Any], ScheduledRestart)
    restartCount += 1
    blackhole = true
    Behaviors.empty
  }

}

private object BackoffSupervisor {
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

