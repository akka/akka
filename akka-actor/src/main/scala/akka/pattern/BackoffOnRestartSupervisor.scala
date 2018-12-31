/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import scala.concurrent.duration._

import akka.actor._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._

/**
 * Back-off supervisor that stops and starts a child actor when the child actor restarts.
 * This back-off supervisor is created by using ``akka.pattern.BackoffSupervisor.props``
 * with ``akka.pattern.Backoff.onFailure``.
 */
private class BackoffOnRestartSupervisor(
  val childProps:        Props,
  val childName:         String,
  minBackoff:            FiniteDuration,
  maxBackoff:            FiniteDuration,
  val reset:             BackoffReset,
  randomFactor:          Double,
  strategy:              OneForOneStrategy,
  val replyWhileStopped: Option[Any],
  val finalStopMessage:  Option[Any ⇒ Boolean])
  extends Actor with HandleBackoff
  with ActorLogging {

  import context._
  import BackoffSupervisor._

  override val supervisorStrategy = OneForOneStrategy(strategy.maxNrOfRetries, strategy.withinTimeRange, strategy.loggingEnabled) {
    case ex ⇒
      val defaultDirective: Directive =
        super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) ⇒ Escalate)

      strategy.decider.applyOrElse(ex, (_: Any) ⇒ defaultDirective) match {

        // Whatever the final Directive is, we will translate all Restarts
        // to our own Restarts, which involves stopping the child.
        case Restart ⇒
          if (strategy.withinTimeRange.isFinite() && restartCount == 0) {
            // If the user has defined a time range for the maxNrOfRetries, we'll schedule a message
            // to ourselves every time that range elapses, to reset the restart counter. We hide it
            // behind this conditional to avoid queuing the message unnecessarily
            val finiteWithinTimeRange = strategy.withinTimeRange.asInstanceOf[FiniteDuration]
            system.scheduler.scheduleOnce(finiteWithinTimeRange, self, ResetRestartCount(restartCount))
          }
          val childRef = sender()
          val nextRestartCount = restartCount + 1
          if (strategy.maxNrOfRetries >= 0 && nextRestartCount > strategy.maxNrOfRetries) {
            // If we've exceeded the maximum # of retries allowed by the Strategy, die.
            log.debug(s"Terminating on restart #{} which exceeds max allowed restarts ({})", nextRestartCount, strategy.maxNrOfRetries)
            become(receive)
            stop(self)
          } else {
            become(waitChildTerminatedBeforeBackoff(childRef) orElse handleBackoff)
          }
          Stop

        case other ⇒ other
      }
  }

  def waitChildTerminatedBeforeBackoff(childRef: ActorRef): Receive = {
    case Terminated(`childRef`) ⇒
      become(receive)
      child = None
      val restartDelay = BackoffSupervisor.calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
      context.system.scheduler.scheduleOnce(restartDelay, self, BackoffSupervisor.StartChild)
      restartCount += 1

    case StartChild ⇒ // Ignore it, we will schedule a new one once current child terminated.
  }

  def onTerminated: Receive = {
    case Terminated(c) ⇒
      log.debug(s"Terminating, because child [$c] terminated itself")
      stop(self)
  }

  def receive = onTerminated orElse handleBackoff

}
