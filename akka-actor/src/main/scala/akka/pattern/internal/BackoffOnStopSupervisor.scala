/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern.internal

import akka.actor.SupervisorStrategy.{ Directive, Escalate }
import akka.actor.{ Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy, Terminated }
import akka.annotation.InternalApi
import akka.pattern.{ BackoffReset, BackoffSupervisor, HandleBackoff }

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 *
 * Back-off supervisor that stops and starts a child actor using a back-off algorithm when the child actor stops.
 * This back-off supervisor is created by using `akka.pattern.BackoffSupervisor.props`
 * with `BackoffOpts.onStop`.
 */
@InternalApi private[pattern] class BackoffOnStopSupervisor(
    val childProps: Props,
    val childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    val reset: BackoffReset,
    randomFactor: Double,
    strategy: SupervisorStrategy,
    replyWhileStopped: Option[Any],
    finalStopMessage: Option[Any => Boolean])
    extends Actor
    with HandleBackoff
    with ActorLogging {

  import BackoffSupervisor._
  import context.dispatcher

  override val supervisorStrategy = strategy match {
    case oneForOne: OneForOneStrategy =>
      OneForOneStrategy(oneForOne.maxNrOfRetries, oneForOne.withinTimeRange, oneForOne.loggingEnabled) {
        case ex =>
          val defaultDirective: Directive =
            super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) => Escalate)

          strategy.decider.applyOrElse(ex, (_: Any) => defaultDirective)
      }
    case s => s
  }

  def onTerminated: Receive = {
    case Terminated(ref) if child.contains(ref) =>
      child = None
      if (finalStopMessageReceived) {
        context.stop(self)
      } else {
        val maxNrOfRetries = strategy match {
          case oneForOne: OneForOneStrategy => oneForOne.maxNrOfRetries
          case _                            => -1
        }

        val nextRestartCount = restartCount + 1

        if (maxNrOfRetries == -1 || nextRestartCount <= maxNrOfRetries) {
          val restartDelay = calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
          context.system.scheduler.scheduleOnce(restartDelay, self, StartChild)
          restartCount = nextRestartCount
        } else {
          log.debug(
            s"Terminating on restart #{} which exceeds max allowed restarts ({})",
            nextRestartCount,
            maxNrOfRetries)
          context.stop(self)
        }
      }

  }

  def receive: Receive = onTerminated.orElse(handleBackoff)

  protected def handleMessageToChild(msg: Any): Unit = child match {
    case Some(c) =>
      c.forward(msg)
      if (!finalStopMessageReceived) finalStopMessage match {
        case Some(fsm) => finalStopMessageReceived = fsm(msg)
        case None      =>
      }
    case None =>
      replyWhileStopped match {
        case Some(r) => sender() ! r
        case None    => context.system.deadLetters.forward(msg)
      }
      finalStopMessage match {
        case Some(fsm) if fsm(msg) => context.stop(self)
        case _                     =>
      }
  }
}
