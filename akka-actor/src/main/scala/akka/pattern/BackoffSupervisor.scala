/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.DeadLetterSuppression
import akka.actor.Props
import akka.actor.Terminated
import java.util.Optional
import scala.concurrent.duration.Duration

object BackoffSupervisor {

  /**
   * Props for creating an [[BackoffSupervisor]] actor.
   *
   * @param childProps the [[akka.actor.Props]] of the child actor that
   *   will be started and supervised
   * @param childName name of the child actor
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   */
  def props(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double): Props = {
    require(minBackoff > Duration.Zero, "minBackoff must be > 0")
    require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
    require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")
    Props(new BackoffSupervisor(childProps, childName, minBackoff, maxBackoff, randomFactor))
  }

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.CurrentChild]] containing the `ActorRef` of the current child, if any.
   */
  final case object GetCurrentChild

  /**
   * Java API: Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.CurrentChild]] containing the `ActorRef` of the current child, if any.
   */
  def getCurrentChild = GetCurrentChild

  final case class CurrentChild(ref: Option[ActorRef]) {
    /**
     * Java API: The `ActorRef` of the current child, if any
     */
    def getRef: Optional[ActorRef] = Optional.ofNullable(ref.orNull)
  }

  private case object StartChild extends DeadLetterSuppression
  private case class ResetRestartCount(current: Int) extends DeadLetterSuppression
}

/**
 * This actor can be used to supervise a child actor and start it again
 * after a back-off duration if the child actor is stopped.
 *
 * This is useful in situations where the re-start of the child actor should be
 * delayed e.g. in order to give an external resource time to recover before the
 * child actor tries contacting it again (after being restarted).
 *
 * Specifically this pattern is useful for for persistent actors,
 * which are stopped in case of persistence failures.
 * Just restarting them immediately would probably fail again (since the data
 * store is probably unavailable). It is better to try again after a delay.
 *
 * It supports exponential back-off between the given `minBackoff` and
 * `maxBackoff` durations. For example, if `minBackoff` is 3 seconds and
 * `maxBackoff` 30 seconds the start attempts will be delayed with
 * 3, 6, 12, 24, 30, 30 seconds. The exponential back-off counter is reset
 * if the actor is not terminated within the `minBackoff` duration.
 *
 * In addition to the calculated exponential back-off an additional
 * random delay based the given `randomFactor` is added, e.g. 0.2 adds up to 20%
 * delay. The reason for adding a random delay is to avoid that all failing
 * actors hit the backend resource at the same time.
 *
 * You can retrieve the current child `ActorRef` by sending `BackoffSupervisor.GetCurrentChild`
 * message to this actor and it will reply with [[akka.pattern.BackoffSupervisor.CurrentChild]]
 * containing the `ActorRef` of the current child, if any.
 *
 * The `BackoffSupervisor` forwards all other messages to the child, if it is currently running.
 *
 * The child can stop itself and send a [[akka.actor.PoisonPill]] to the parent supervisor
 * if it wants to do an intentional stop.
 */
final class BackoffSupervisor(
  childProps: Props,
  childName: String,
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double)
  extends Actor {

  import BackoffSupervisor._
  import context.dispatcher

  private var child: Option[ActorRef] = None
  private var restartCount = 0

  override def preStart(): Unit =
    startChild()

  def startChild(): Unit =
    if (child.isEmpty) {
      child = Some(context.watch(context.actorOf(childProps, childName)))
    }

  def receive = {
    case Terminated(ref) if child.contains(ref) ⇒
      child = None
      val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
      val restartDelay =
        if (restartCount >= 30) // Duration overflow protection (> 100 years)
          maxBackoff
        else
          maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd match {
            case f: FiniteDuration ⇒ f
            case _                 ⇒ maxBackoff
          }
      context.system.scheduler.scheduleOnce(restartDelay, self, StartChild)
      restartCount += 1

    case StartChild ⇒
      startChild()
      context.system.scheduler.scheduleOnce(minBackoff, self, ResetRestartCount(restartCount))

    case ResetRestartCount(current) ⇒
      if (current == restartCount)
        restartCount = 0

    case GetCurrentChild ⇒
      sender() ! CurrentChild(child)

    case msg ⇒ child match {
      case Some(c) ⇒ c.forward(msg)
      case None    ⇒ context.system.deadLetters.forward(msg)
    }
  }
}

