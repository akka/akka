/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

import akka.actor.DeadLetterSuppression
import akka.actor.Dropped
import akka.actor.typed.BehaviorInterceptor.PreStartTarget
import akka.actor.typed.BehaviorInterceptor.ReceiveTarget
import akka.actor.typed.BehaviorInterceptor.SignalTarget
import akka.actor.typed.SupervisorStrategy._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.StashBuffer
import akka.annotation.InternalApi
import akka.event.Logging
import akka.util.OptionVal
import akka.util.unused
import org.slf4j.event.Level

/**
 * INTERNAL API
 */
@InternalApi private[akka] object Supervisor {
  def apply[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], strategy: SupervisorStrategy): Behavior[T] = {
    if (initialBehavior.isInstanceOf[scaladsl.AbstractBehavior[_]] || initialBehavior
          .isInstanceOf[javadsl.AbstractBehavior[_]]) {
      throw new IllegalArgumentException(
        "The supervised Behavior must not be a AbstractBehavior instance directly," +
        "because a different instance should be created when it is restarted. Wrap in Behaviors.setup.")
    }

    strategy match {
      case r: RestartOrBackoff =>
        Behaviors.intercept[Any, T](() => new RestartSupervisor(initialBehavior, r))(initialBehavior).narrow
      case r: Resume =>
        // stateless so safe to share
        Behaviors.intercept[Any, T](() => new ResumeSupervisor(r))(initialBehavior).narrow
      case r: Stop =>
        // stateless so safe to share
        Behaviors.intercept[Any, T](() => new StopSupervisor(initialBehavior, r))(initialBehavior).narrow
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private abstract class AbstractSupervisor[I, Thr <: Throwable](strategy: SupervisorStrategy)(implicit ev: ClassTag[Thr])
    extends BehaviorInterceptor[Any, I] {

  private val throwableClass = implicitly[ClassTag[Thr]].runtimeClass

  protected def isInstanceOfTheThrowableClass(t: Throwable): Boolean =
    throwableClass.isAssignableFrom(UnstashException.unwrap(t).getClass)

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = {
    other match {
      case as: AbstractSupervisor[_, Thr] if throwableClass == as.throwableClass => true
      case _                                                                     => false
    }
  }

  override def aroundStart(ctx: TypedActorContext[Any], target: PreStartTarget[I]): Behavior[I] = {
    try {
      target.start(ctx)
    } catch handleExceptionOnStart(ctx, target)
  }

  override def aroundSignal(ctx: TypedActorContext[Any], signal: Signal, target: SignalTarget[I]): Behavior[I] = {
    try {
      target(ctx, signal)
    } catch handleSignalException(ctx, target)
  }

  def log(ctx: TypedActorContext[_], t: Throwable): Unit = {
    if (strategy.loggingEnabled) {
      val unwrapped = UnstashException.unwrap(t)
      val logMessage = s"Supervisor $this saw failure: ${unwrapped.getMessage}"
      val logger = ctx.asScala.log
      strategy.logLevel match {
        case Level.ERROR => logger.error(logMessage, unwrapped)
        case Level.WARN  => logger.warn(logMessage, unwrapped)
        case Level.INFO  => logger.info(logMessage, unwrapped)
        case Level.DEBUG => logger.debug(logMessage, unwrapped)
        case Level.TRACE => logger.trace(logMessage, unwrapped)
        case other       => throw new IllegalArgumentException(s"Unknown log level [$other].")
      }
    }
  }

  def dropped(ctx: TypedActorContext[_], signalOrMessage: Any): Unit = {
    import akka.actor.typed.scaladsl.adapter._
    ctx.asScala.system.toClassic.eventStream
      .publish(Dropped(signalOrMessage, s"Stash is full in [${getClass.getSimpleName}]", ctx.asScala.self.toClassic))
  }

  protected def handleExceptionOnStart(ctx: TypedActorContext[Any], target: PreStartTarget[I]): Catcher[Behavior[I]]
  protected def handleSignalException(ctx: TypedActorContext[Any], target: SignalTarget[I]): Catcher[Behavior[I]]
  protected def handleReceiveException(ctx: TypedActorContext[Any], target: ReceiveTarget[I]): Catcher[Behavior[I]]

  override def toString: String = Logging.simpleName(getClass)
}

/**
 * For cases where O == I for BehaviorInterceptor.
 */
private abstract class SimpleSupervisor[T, Thr <: Throwable: ClassTag](ss: SupervisorStrategy)
    extends AbstractSupervisor[T, Thr](ss) {

  override def aroundReceive(ctx: TypedActorContext[Any], msg: Any, target: ReceiveTarget[T]): Behavior[T] = {
    try {
      target(ctx, msg.asInstanceOf[T])
    } catch handleReceiveException(ctx, target)
  }

  protected def handleException(@unused ctx: TypedActorContext[Any]): Catcher[Behavior[T]] = {
    case NonFatal(t) if isInstanceOfTheThrowableClass(t) =>
      BehaviorImpl.failed(t)
  }

  // convenience if target not required to handle exception
  protected def handleExceptionOnStart(ctx: TypedActorContext[Any], target: PreStartTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleSignalException(ctx: TypedActorContext[Any], target: SignalTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleReceiveException(ctx: TypedActorContext[Any], target: ReceiveTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
}

private class StopSupervisor[T, Thr <: Throwable: ClassTag](@unused initial: Behavior[T], strategy: Stop)
    extends SimpleSupervisor[T, Thr](strategy) {

  override def handleException(ctx: TypedActorContext[Any]): Catcher[Behavior[T]] = {
    case NonFatal(t) if isInstanceOfTheThrowableClass(t) =>
      log(ctx, t)
      BehaviorImpl.failed(t)
  }
}

private class ResumeSupervisor[T, Thr <: Throwable: ClassTag](ss: Resume) extends SimpleSupervisor[T, Thr](ss) {
  override protected def handleException(ctx: TypedActorContext[Any]): Catcher[Behavior[T]] = {
    case NonFatal(t) if isInstanceOfTheThrowableClass(t) =>
      log(ctx, t)
      t match {
        case e: UnstashException[T] @unchecked => e.behavior
        case _                                 => Behaviors.same
      }
  }
}

private object RestartSupervisor {

  /**
   * Calculates an exponential back off delay.
   */
  def calculateDelay(
      restartCount: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd match {
        case f: FiniteDuration => f
        case _                 => maxBackoff
      }
  }

  final case class ScheduledRestart(owner: RestartSupervisor[_, _ <: Throwable]) extends DeadLetterSuppression
  final case class ResetRestartCount(current: Int, owner: RestartSupervisor[_, _ <: Throwable])
      extends DeadLetterSuppression
}

private class RestartSupervisor[T, Thr <: Throwable: ClassTag](initial: Behavior[T], strategy: RestartOrBackoff)
    extends AbstractSupervisor[T, Thr](strategy) {
  import RestartSupervisor._

  private var restartingInProgress: OptionVal[(StashBuffer[Any], Set[ActorRef[Nothing]])] = OptionVal.None
  private var restartCount: Int = 0
  private var gotScheduledRestart = true
  private var deadline: OptionVal[Deadline] = OptionVal.None

  private def deadlineHasTimeLeft: Boolean = deadline match {
    case OptionVal.None    => true
    case OptionVal.Some(d) => d.hasTimeLeft
  }

  override def aroundSignal(ctx: TypedActorContext[Any], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    restartingInProgress match {
      case OptionVal.None =>
        super.aroundSignal(ctx, signal, target)
      case OptionVal.Some((stashBuffer, children)) =>
        signal match {
          case Terminated(ref) if strategy.stopChildren && children(ref) =>
            val remainingChildren = children - ref
            if (remainingChildren.isEmpty && gotScheduledRestart) {
              restartCompleted(ctx)
            } else {
              restartingInProgress = OptionVal.Some((stashBuffer, remainingChildren))
              Behaviors.same
            }

          case _ =>
            if (stashBuffer.isFull)
              dropped(ctx, signal)
            else
              stashBuffer.stash(signal)
            Behaviors.same
        }
    }
  }

  override def aroundReceive(ctx: TypedActorContext[Any], msg: Any, target: ReceiveTarget[T]): Behavior[T] = {
    msg match {
      case ScheduledRestart(owner) =>
        if (owner eq this) {
          restartingInProgress match {
            case OptionVal.Some((_, children)) =>
              if (strategy.stopChildren && children.nonEmpty) {
                // still waiting for children to stop
                gotScheduledRestart = true
                Behaviors.same
              } else
                restartCompleted(ctx)

            case OptionVal.None =>
              throw new IllegalStateException("Unexpected ScheduledRestart when restart not in progress")
          }
        } else {
          // ScheduledRestart from nested Backoff strategy
          target(ctx, msg.asInstanceOf[T])
        }

      case ResetRestartCount(current, owner) =>
        if (owner eq this) {
          if (current == restartCount) {
            restartCount = 0
          }
          BehaviorImpl.same
        } else {
          // ResetRestartCount from nested Backoff strategy
          target(ctx, msg.asInstanceOf[T])
        }

      case m: T @unchecked =>
        restartingInProgress match {
          case OptionVal.None =>
            try {
              target(ctx, m)
            } catch handleReceiveException(ctx, target)
          case OptionVal.Some((stashBuffer, _)) =>
            if (stashBuffer.isFull)
              dropped(ctx, m)
            else
              stashBuffer.stash(m)
            Behaviors.same
        }
    }
  }

  override protected def handleExceptionOnStart(
      ctx: TypedActorContext[Any],
      @unused target: PreStartTarget[T]): Catcher[Behavior[T]] = {
    case NonFatal(t) if isInstanceOfTheThrowableClass(t) =>
      ctx.asScala.cancelAllTimers()
      strategy match {
        case _: Restart =>
          // if unlimited restarts then don't restart if starting fails as it would likely be an infinite restart loop
          if (strategy.unlimitedRestarts() || ((restartCount + 1) >= strategy.maxRestarts && deadlineHasTimeLeft)) {
            // don't log here as it'll be logged as ActorInitializationException
            throw t
          } else {
            prepareRestart(ctx, t)
          }
        case _: Backoff =>
          prepareRestart(ctx, t)
      }
  }

  override protected def handleSignalException(
      ctx: TypedActorContext[Any],
      target: SignalTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, signalRestart = {
      case e: UnstashException[Any] @unchecked => Behavior.interpretSignal(e.behavior, ctx, PreRestart)
      case _                                   => target(ctx, PreRestart)
    })
  }
  override protected def handleReceiveException(
      ctx: TypedActorContext[Any],
      target: ReceiveTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, signalRestart = {
      case e: UnstashException[Any] @unchecked => Behavior.interpretSignal(e.behavior, ctx, PreRestart)
      case _                                   => target.signalRestart(ctx)
    })
  }

  private def handleException(ctx: TypedActorContext[Any], signalRestart: Throwable => Unit): Catcher[Behavior[T]] = {
    case NonFatal(t) if isInstanceOfTheThrowableClass(t) =>
      ctx.asScala.cancelAllTimers()
      if (strategy.maxRestarts != -1 && restartCount >= strategy.maxRestarts && deadlineHasTimeLeft) {
        strategy match {
          case _: Restart => throw t
          case _: Backoff =>
            log(ctx, t)
            BehaviorImpl.failed(t)
        }

      } else {
        try signalRestart(t)
        catch {
          case NonFatal(ex) => ctx.asScala.log.error("failure during PreRestart", ex)
        }

        prepareRestart(ctx, t)
      }
  }

  private def prepareRestart(ctx: TypedActorContext[Any], reason: Throwable): Behavior[T] = {
    log(ctx, reason)

    val currentRestartCount = restartCount
    updateRestartCount()

    val childrenToStop = if (strategy.stopChildren) ctx.asScala.children.toSet else Set.empty[ActorRef[Nothing]]
    stopChildren(ctx, childrenToStop)

    val stashCapacity =
      if (strategy.stashCapacity >= 0) strategy.stashCapacity
      else ctx.asScala.system.settings.RestartStashCapacity
    restartingInProgress = OptionVal.Some(
      (StashBuffer[Any](ctx.asScala.asInstanceOf[scaladsl.ActorContext[Any]], stashCapacity), childrenToStop))

    strategy match {
      case backoff: Backoff =>
        val restartDelay =
          calculateDelay(currentRestartCount, backoff.minBackoff, backoff.maxBackoff, backoff.randomFactor)
        gotScheduledRestart = false
        ctx.asScala.scheduleOnce(restartDelay, ctx.asScala.self, ScheduledRestart(this))
        Behaviors.empty
      case _: Restart =>
        if (childrenToStop.isEmpty)
          restartCompleted(ctx)
        else
          Behaviors.empty // wait for termination of children
    }
  }

  private def restartCompleted(ctx: TypedActorContext[Any]): Behavior[T] = {
    // probably already done, but doesn't hurt to make sure they are canceled
    ctx.asScala.cancelAllTimers()

    strategy match {
      case backoff: Backoff =>
        gotScheduledRestart = false
        ctx.asScala.scheduleOnce(backoff.resetBackoffAfter, ctx.asScala.self, ResetRestartCount(restartCount, this))
      case _: Restart =>
    }

    try {
      val newBehavior = Behavior.validateAsInitial(Behavior.start(initial, ctx.asInstanceOf[TypedActorContext[T]]))
      val nextBehavior = restartingInProgress match {
        case OptionVal.None => newBehavior
        case OptionVal.Some((stashBuffer, _)) =>
          restartingInProgress = OptionVal.None
          stashBuffer.unstashAll(newBehavior.unsafeCast)
      }
      nextBehavior.narrow
    } catch handleException(ctx, signalRestart = {
      case e: UnstashException[Any] @unchecked => Behavior.interpretSignal(e.behavior, ctx, PreRestart)
      case _                                   => ()
    })
  }

  private def stopChildren(ctx: TypedActorContext[_], children: Set[ActorRef[Nothing]]): Unit = {
    children.foreach { child =>
      ctx.asScala.watch(child)
      ctx.asScala.stop(child)
    }
  }

  private def updateRestartCount(): Unit = {
    strategy match {
      case restart: Restart =>
        val timeLeft = deadlineHasTimeLeft
        val newDeadline =
          if (deadline.isDefined && timeLeft) deadline else OptionVal.Some(Deadline.now + restart.withinTimeRange)
        restartCount = if (timeLeft) restartCount + 1 else 1
        deadline = newDeadline
      case _: Backoff =>
        restartCount += 1
    }
  }

}
