/*
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import java.util.concurrent.ThreadLocalRandom
import java.util.Optional

import akka.actor.{ Actor, ActorLogging, ActorRef, DeadLetterSuppression, OneForOneStrategy, Props, SupervisorStrategy, Terminated }
import akka.actor.SupervisorStrategy.{ Directive, Escalate }
import akka.util.JavaDurationConverters._

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

object BackoffSupervisor {

  /**
   * Props for creating a [[BackoffSupervisor]] actor.
   *
   * Exceptions in the child are handled with the default supervision strategy, i.e.
   * most exceptions will immediately restart the child. You can define another
   * supervision strategy by using [[#propsWithSupervisorStrategy]].
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
    childProps:   Props,
    childName:    String,
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double): Props = {
    propsWithSupervisorStrategy(childProps, childName, minBackoff, maxBackoff, randomFactor, SupervisorStrategy.defaultStrategy)
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor.
   *
   * Exceptions in the child are handled with the default supervision strategy, i.e.
   * most exceptions will immediately restart the child. You can define another
   * supervision strategy by using [[#propsWithSupervisorStrategy]].
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
   * @param maxNrOfRetries maximum number of attempts to restart the child actor.
   *   The supervisor will terminate itself after the maxNoOfRetries is reached.
   *   In order to restart infinitely pass in `-1`.
   */
  def props(
    childProps:     Props,
    childName:      String,
    minBackoff:     FiniteDuration,
    maxBackoff:     FiniteDuration,
    randomFactor:   Double,
    maxNrOfRetries: Int): Props = {
    val supervisionStrategy = SupervisorStrategy.defaultStrategy match {
      case oneForOne: OneForOneStrategy ⇒ oneForOne.withMaxNrOfRetries(maxNrOfRetries)
      case s                            ⇒ s
    }
    propsWithSupervisorStrategy(childProps, childName, minBackoff, maxBackoff, randomFactor, supervisionStrategy)
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor.
   *
   * Exceptions in the child are handled with the default supervision strategy, i.e.
   * most exceptions will immediately restart the child. You can define another
   * supervision strategy by using [[#propsWithSupervisorStrategy]].
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
    childProps:   Props,
    childName:    String,
    minBackoff:   java.time.Duration,
    maxBackoff:   java.time.Duration,
    randomFactor: Double): Props = {
    props(childProps, childName, minBackoff.asScala, maxBackoff.asScala, randomFactor)
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor.
   *
   * Exceptions in the child are handled with the default supervision strategy, i.e.
   * most exceptions will immediately restart the child. You can define another
   * supervision strategy by using [[#propsWithSupervisorStrategy]].
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
   * @param maxNrOfRetries maximum number of attempts to restart the child actor.
   *   The supervisor will terminate itself after the maxNoOfRetries is reached.
   *   In order to restart infinitely pass in `-1`.
   */
  def props(
    childProps:     Props,
    childName:      String,
    minBackoff:     java.time.Duration,
    maxBackoff:     java.time.Duration,
    randomFactor:   Double,
    maxNrOfRetries: Int): Props = {
    props(childProps, childName, minBackoff.asScala, maxBackoff.asScala, randomFactor, maxNrOfRetries)
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor with a custom
   * supervision strategy.
   *
   * Exceptions in the child are handled with the given `supervisionStrategy`. A
   * `Restart` will perform a normal immediate restart of the child. A `Stop` will
   * stop the child, but it will be started again after the back-off duration.
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
   * @param strategy the supervision strategy to use for handling exceptions
   *   in the child. As the BackoffSupervisor creates a separate actor to handle the
   *   backoff process, only a [[OneForOneStrategy]] makes sense here.
   */
  def propsWithSupervisorStrategy(
    childProps:   Props,
    childName:    String,
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double,
    strategy:     SupervisorStrategy): Props = {
    require(minBackoff > Duration.Zero, "minBackoff must be > 0")
    require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
    require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")
    Props(new BackoffSupervisor(childProps, childName, minBackoff, maxBackoff, randomFactor, strategy))
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor with a custom
   * supervision strategy.
   *
   * Exceptions in the child are handled with the given `supervisionStrategy`. A
   * `Restart` will perform a normal immediate restart of the child. A `Stop` will
   * stop the child, but it will be started again after the back-off duration.
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
   * @param strategy the supervision strategy to use for handling exceptions
   *   in the child. As the BackoffSupervisor creates a separate actor to handle the
   *   backoff process, only a [[OneForOneStrategy]] makes sense here.
   */
  def propsWithSupervisorStrategy(
    childProps:   Props,
    childName:    String,
    minBackoff:   java.time.Duration,
    maxBackoff:   java.time.Duration,
    randomFactor: Double,
    strategy:     SupervisorStrategy): Props = {
    propsWithSupervisorStrategy(childProps, childName, minBackoff.asScala, maxBackoff.asScala, randomFactor, strategy)
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor from [[BackoffOptions]].
   *
   * @param options the [[BackoffOptions]] that specify how to construct a backoff-supervisor.
   */
  def props(options: BackoffOptions): Props = options.props

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

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.CurrentChild]] containing the `ActorRef` of the current child, if any.
   */
  final case class CurrentChild(ref: Option[ActorRef]) {
    /**
     * Java API: The `ActorRef` of the current child, if any
     */
    def getRef: Optional[ActorRef] = Optional.ofNullable(ref.orNull)
  }

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reset the back-off.
   * This should be used in conjunction with `withManualReset` in [[BackoffOptions]].
   */
  final case object Reset

  /**
   * Java API: Send this message to the [[BackoffSupervisor]] and it will reset the back-off.
   * This should be used in conjunction with `withManualReset` in [[BackoffOptions]].
   */
  def reset = Reset

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.RestartCount]] containing the current restart count.
   */
  final case object GetRestartCount

  /**
   * Java API: Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.RestartCount]] containing the current restart count.
   */
  def getRestartCount = GetRestartCount

  final case class RestartCount(count: Int)

  // not final for binary compatibility with 2.4.1
  private[akka] case object StartChild extends DeadLetterSuppression

  // not final for binary compatibility with 2.4.1
  private[akka] case class ResetRestartCount(current: Int) extends DeadLetterSuppression

  /**
   * INTERNAL API
   *
   * Calculates an exponential back off delay.
   */
  private[akka] def calculateDelay(
    restartCount: Int,
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    val calculatedDuration = Try(maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd).getOrElse(maxBackoff)
    calculatedDuration match {
      case f: FiniteDuration ⇒ f
      case _                 ⇒ maxBackoff
    }
  }
}

/**
 * Back-off supervisor that stops and starts a child actor using a back-off algorithm when the child actor stops.
 * This back-off supervisor is created by using `akka.pattern.BackoffSupervisor.props`
 * with `Backoff.onStop`.
 */
final class BackoffSupervisor(
  val childProps:        Props,
  val childName:         String,
  minBackoff:            FiniteDuration,
  maxBackoff:            FiniteDuration,
  val reset:             BackoffReset,
  randomFactor:          Double,
  strategy:              SupervisorStrategy,
  val replyWhileStopped: Option[Any],
  val finalStopMessage:  Option[Any ⇒ Boolean])
  extends Actor with HandleBackoff
  with ActorLogging {

  import BackoffSupervisor._
  import context.dispatcher

  // to keep binary compatibility with 2.4.1
  override val supervisorStrategy = strategy match {
    case oneForOne: OneForOneStrategy ⇒
      OneForOneStrategy(oneForOne.maxNrOfRetries, oneForOne.withinTimeRange, oneForOne.loggingEnabled) {
        case ex ⇒
          val defaultDirective: Directive =
            super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) ⇒ Escalate)

          strategy.decider.applyOrElse(ex, (_: Any) ⇒ defaultDirective)
      }
    case s ⇒ s
  }

  // for binary compatibility with 2.5.18
  def this(
    childProps:        Props,
    childName:         String,
    minBackoff:        FiniteDuration,
    maxBackoff:        FiniteDuration,
    reset:             BackoffReset,
    randomFactor:      Double,
    strategy:          SupervisorStrategy,
    replyWhileStopped: Option[Any]) = this(childProps, childName, minBackoff, maxBackoff, reset, randomFactor, strategy, replyWhileStopped, None)

  // for binary compatibility with 2.4.1
  def this(
    childProps:         Props,
    childName:          String,
    minBackoff:         FiniteDuration,
    maxBackoff:         FiniteDuration,
    randomFactor:       Double,
    supervisorStrategy: SupervisorStrategy) =
    this(childProps, childName, minBackoff, maxBackoff, AutoReset(minBackoff), randomFactor, supervisorStrategy, None, None)

  // for binary compatibility with 2.4.0
  def this(
    childProps:   Props,
    childName:    String,
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double) =
    this(childProps, childName, minBackoff, maxBackoff, randomFactor, SupervisorStrategy.defaultStrategy)

  def onTerminated: Receive = {
    case Terminated(ref) if child.contains(ref) ⇒
      child = None
      if (finalStopMessageReceived) {
        context.stop(self)
      } else {
        val maxNrOfRetries = strategy match {
          case oneForOne: OneForOneStrategy ⇒ oneForOne.maxNrOfRetries
          case _                            ⇒ -1
        }

        val nextRestartCount = restartCount + 1

        if (maxNrOfRetries == -1 || nextRestartCount <= maxNrOfRetries) {
          val restartDelay = calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
          context.system.scheduler.scheduleOnce(restartDelay, self, StartChild)
          restartCount = nextRestartCount
        } else {
          log.debug(s"Terminating on restart #{} which exceeds max allowed restarts ({})", nextRestartCount, maxNrOfRetries)
          context.stop(self)
        }
      }

  }

  def receive = onTerminated orElse handleBackoff
}

private[akka] trait HandleBackoff { this: Actor ⇒
  def childProps: Props
  def childName: String
  def reset: BackoffReset
  def replyWhileStopped: Option[Any]
  def finalStopMessage: Option[Any ⇒ Boolean]

  var child: Option[ActorRef] = None
  var restartCount = 0
  var finalStopMessageReceived = false

  import BackoffSupervisor._
  import context.dispatcher

  override def preStart(): Unit = startChild()

  def startChild(): Unit = {
    if (child.isEmpty) {
      child = Some(context.watch(context.actorOf(childProps, childName)))
    }
  }

  def handleBackoff: Receive = {
    case StartChild ⇒
      startChild()
      reset match {
        case AutoReset(resetBackoff) ⇒
          val _ = context.system.scheduler.scheduleOnce(resetBackoff, self, ResetRestartCount(restartCount))
        case _ ⇒ // ignore
      }

    case Reset ⇒
      reset match {
        case ManualReset ⇒ restartCount = 0
        case msg         ⇒ unhandled(msg)
      }

    case ResetRestartCount(current) ⇒
      if (current == restartCount) {
        restartCount = 0
      }

    case GetRestartCount ⇒
      sender() ! RestartCount(restartCount)

    case GetCurrentChild ⇒
      sender() ! CurrentChild(child)

    case msg if child.contains(sender()) ⇒
      // use the BackoffSupervisor as sender
      context.parent ! msg

    case msg ⇒ child match {
      case Some(c) ⇒
        c.forward(msg)
        if (!finalStopMessageReceived && finalStopMessage.isDefined) {
          finalStopMessageReceived = finalStopMessage.get.apply(msg)
        }
      case None ⇒
        replyWhileStopped match {
          case None    ⇒ context.system.deadLetters.forward(msg)
          case Some(r) ⇒ sender() ! r
        }
        finalStopMessage match {
          case None ⇒
          case Some(fsm) ⇒
            if (fsm(msg)) {
              context.stop(self)
            }
        }
    }
  }
}
