/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.DeadLetter
import akka.event.Logging.Info
import akka.util.PrettyDuration._

class DeadLetterListener extends Actor {

  val eventStream: EventStream = context.system.eventStream
  protected val maxCount: Int = context.system.settings.LogDeadLetters
  private val isAlwaysLoggingDeadLetters = maxCount == Int.MaxValue
  protected var count: Int = 0

  override def preStart(): Unit =
    eventStream.subscribe(self, classOf[DeadLetter])

  // don't re-subscribe, skip call to preStart
  override def postRestart(reason: Throwable): Unit = ()

  // don't remove subscription, skip call to postStop, no children to stop
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = ()

  override def postStop(): Unit =
    eventStream.unsubscribe(self)

  private def incrementCount(): Unit = {
    // `count` is public API (for unknown reason) so for backwards compatibility reasons we
    // can't change it to Long
    if (count == Int.MaxValue) {
      Logging.getLogger(this).info("Resetting DeadLetterListener counter after reaching Int.MaxValue.")
      count = 1
    } else
      count += 1
  }

  def receive: Receive =
    if (isAlwaysLoggingDeadLetters) receiveWithAlwaysLogging
    else
      context.system.settings.LogDeadLettersSuspendDuration match {
        case suspendDuration: FiniteDuration => receiveWithSuspendLogging(suspendDuration)
        case _                               => receiveWithMaxCountLogging
      }

  private def receiveWithAlwaysLogging: Receive = {
    case DeadLetter(message, snd, recipient) =>
      incrementCount()
      logDeadLetter(message, snd, recipient, doneMsg = "")
  }

  private def receiveWithMaxCountLogging: Receive = {
    case DeadLetter(message, snd, recipient) =>
      incrementCount()
      if (count == maxCount) {
        logDeadLetter(message, snd, recipient, ", no more dead letters will be logged")
        context.stop(self)
      } else {
        logDeadLetter(message, snd, recipient, "")
      }
  }

  private def receiveWithSuspendLogging(suspendDuration: FiniteDuration): Receive = {
    case DeadLetter(message, snd, recipient) =>
      incrementCount()
      if (count == maxCount) {
        val doneMsg = s", no more dead letters will be logged in next [${suspendDuration.pretty}]"
        logDeadLetter(message, snd, recipient, doneMsg)
        context.become(receiveWhenSuspended(suspendDuration, Deadline.now + suspendDuration))
      } else
        logDeadLetter(message, snd, recipient, "")
  }

  private def receiveWhenSuspended(suspendDuration: FiniteDuration, suspendDeadline: Deadline): Receive = {
    case DeadLetter(message, snd, recipient) =>
      incrementCount()
      if (suspendDeadline.isOverdue()) {
        val doneMsg = s", of which ${count - maxCount - 1} were not logged. The counter will be reset now"
        logDeadLetter(message, snd, recipient, doneMsg)
        count = 0
        context.become(receiveWithSuspendLogging(suspendDuration))
      }
  }

  private def logDeadLetter(message: Any, snd: ActorRef, recipient: ActorRef, doneMsg: String): Unit = {
    val origin = if (snd eq context.system.deadLetters) "without sender" else s"from $snd"
    eventStream.publish(
      Info(
        recipient.path.toString,
        recipient.getClass,
        s"Message [${message.getClass.getName}] $origin to $recipient was not delivered. [$count] dead letters encountered$doneMsg. " +
        s"If this is not an expected behavior then $recipient may have terminated unexpectedly. " +
        "This logging can be turned off or adjusted with configuration settings 'akka.log-dead-letters' " +
        "and 'akka.log-dead-letters-during-shutdown'."))
  }

}
