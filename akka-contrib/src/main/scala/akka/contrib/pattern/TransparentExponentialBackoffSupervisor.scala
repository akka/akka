/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.actor._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.concurrent.duration._

object TransparentExponentialBackoffSupervisor {
  private case class ScheduleRestart(childRef: ActorRef) extends DeadLetterSuppression
  private case object StartChild extends DeadLetterSuppression
  private case class ResetRestartCount(lastNumRestarts: Int) extends DeadLetterSuppression

  /**
   * Props for creating a [[TransparentExponentialBackoffSupervisor]] with a decider.
   *
   * @param childProps the [[akka.actor.Props]] of the child to be supervised.
   * @param childName the name of the child actor.
   * @param minBackoff the min time before the child is restarted.
   * @param maxBackoff the max time (upperbound) for a child restart.
   * @param randomFactor a random delay factor to add on top of the calculated exponential
   *   back off.
   *   The calculation is equivalent to:
   *   {{{
   *     final_delay = min(
   *       maxBackoff,
   *       (random_delay_factor * calculated_backoff) + calculated_backoff)
   *   }}}
   * @param decider a `Decider` to specify how the supervisor
   *   should behave for different exceptions. If no cases are matched, the default decider of
   *   [[akka.actor.Actor]] is used. When the `Restart` directive
   *   is returned by the decider, this supervisor will apply an exponential back off restart.
   */
  def propsWithDecider(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double)(decider: Decider): Props = {
    Props(
      new TransparentExponentialBackoffSupervisor(
        childProps,
        childName,
        Some(decider),
        minBackoff,
        maxBackoff,
        randomFactor))
  }

  /**
   * Props for creating a [[TransparentExponentialBackoffSupervisor]] using the
   * default [[akka.actor.Actor]] decider.
   *
   * @param childProps the [[akka.actor.Props]] of the child to be supervised.
   * @param childName the name of the child actor.
   * @param minBackoff the min time before the child is restarted.
   * @param maxBackoff the max time (upperbound) for a child restart.
   * @param randomFactor a random delay factor to add on top of the calculated exponential
   *   back off.
   *   The calculation is equivalent to:
   *   {{{
   *     final_delay = min(
   *       maxBackoff,
   *       (random_delay_factor * calculated_backoff) + calculated_backoff)
   *   }}}
   */
  def props(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double): Props = {
    Props(
      new TransparentExponentialBackoffSupervisor(
        childProps,
        childName,
        None,
        minBackoff,
        maxBackoff,
        randomFactor))
  }
}

/**
 * A supervising actor that restarts a child actor with an exponential back off.
 *
 * This explicit supervisor behaves similarly to the normal implicit supervision where
 * if an actor throws an exception, the decider on the supervisor will decide when to
 * `Stop`, `Restart`, `Escalate`, `Resume` the child actor.
 *
 * When the `Restart` directive is specified, the supervisor will delay the restart
 * using an exponential back off strategy (bounded by minBackoff and maxBackoff).
 *
 * This supervisor is intended to be transparent to both the child actor and external actors.
 * Where external actors can send messages to the supervisor as if it was the child and the
 * messages will be forwarded. And when the child is `Terminated`, the supervisor is also
 * `Terminated`.
 * Transparent to the child means that the child does not have to be aware that it is being
 * supervised specifically by the [[TransparentExponentialBackoffSupervisor]]. Just like it does
 * not need to know when it is being supervised by the usual implicit supervisors.
 * The only caveat is that the `ActorRef` of the child is not stable, so any user storing the
 * `sender()` `ActorRef` from the child response may eventually not be able to communicate with
 * the stored `ActorRef`. In general all messages to the child should be directed through the
 * [[TransparentExponentialBackoffSupervisor]].
 *
 * An example of where this supervisor might be used is when you may have an actor that is
 * responsible for continuously polling on a server for some resource that sometimes may be down.
 * Instead of hammering the server continuously when the resource is unavailable, the actor will
 * be restarted with an exponentially increasing back off until the resource is available again.
 *
 * '''***
 * This supervisor should not be used with `Akka Persistence` child actors.
 * `Akka Persistence` actors, currently, shutdown unconditionally on `persistFailure()`s rather
 * than throw an exception on a failure like normal actors.
 * [[akka.pattern.BackoffSupervisor]] should be used instead for cases where the child actor
 * terminates itself as a failure signal instead of the normal behavior of throwing an exception.
 * ***'''
 */
class TransparentExponentialBackoffSupervisor(
  props: Props,
  childName: String,
  decider: Option[Decider],
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double)
  extends Actor
  with Stash
  with ActorLogging {

  import TransparentExponentialBackoffSupervisor._
  import context._

  override val supervisorStrategy = OneForOneStrategy() {
    case ex ⇒
      val defaultDirective: Directive =
        super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) ⇒ Escalate)
      val maybeDirective: Option[Directive] = decider
        .map(_.applyOrElse(ex, (_: Any) ⇒ defaultDirective))

      // Get the directive from the specified decider or fallback to
      // the default decider.
      // Whatever the final Directive is, we will translate all Restarts
      // to our own Restarts, which involves stopping the child.
      maybeDirective.getOrElse(defaultDirective) match {
        case Restart ⇒
          val childRef = sender
          become({
            case Terminated(`childRef`) ⇒
              unbecome()
              self ! ScheduleRestart(childRef)
            case _ ⇒
              stash()
          }, discardOld = false)
          Stop
        case other ⇒ other
      }
  }

  // Initialize by starting up and watching the child
  self ! StartChild

  def receive = waitingToStart(-1, false)

  def waitingToStart(numRestarts: Int, scheduleCounterReset: Boolean): Receive = {
    case StartChild ⇒
      val childRef = actorOf(props, childName)
      watch(childRef)
      unstashAll()
      if (scheduleCounterReset) {
        system.scheduler.scheduleOnce(minBackoff, self, ResetRestartCount(numRestarts + 1))
      }
      become(watching(childRef, numRestarts + 1))
    case _ ⇒ stash()
  }

  // Steady state
  def watching(childRef: ActorRef, numRestarts: Int): Receive = {
    case ScheduleRestart(`childRef`) ⇒
      val delay = akka.pattern.BackoffSupervisor.calculateDelay(
        numRestarts, minBackoff, maxBackoff, randomFactor)
      system.scheduler.scheduleOnce(delay, self, StartChild)
      become(waitingToStart(numRestarts, true))
      log.info(s"Restarting child in: $delay; numRestarts: $numRestarts")
    case ResetRestartCount(last) ⇒
      if (last == numRestarts) {
        log.debug(s"Last restart count [$last] matches current count; resetting")
        become(watching(childRef, 0))
      } else {
        log.debug(s"Last restart count [$last] does not match the current count [$numRestarts]")
      }
    case Terminated(`childRef`) ⇒
      log.debug(s"Terminating, because child [$childRef] terminated itself")
      stop(self)
    case msg if sender() == childRef ⇒
      // use the supervisor as sender
      context.parent ! msg
    case msg ⇒
      childRef.forward(msg)
  }
}
