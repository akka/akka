/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
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
  val childProps: Props,
  val childName:  String,
  minBackoff:     FiniteDuration,
  maxBackoff:     FiniteDuration,
  val reset:      BackoffReset,
  randomFactor:   Double,
  strategy:       OneForOneStrategy)
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
          val childRef = sender()
          become(waitChildTerminatedBeforeBackoff(childRef) orElse handleBackoff)
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
    case Terminated(child) ⇒
      log.debug(s"Terminating, because child [$child] terminated itself")
      stop(self)
  }

  def receive = onTerminated orElse handleBackoff
}
