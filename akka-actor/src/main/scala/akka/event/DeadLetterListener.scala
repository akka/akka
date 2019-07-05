/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.AllDeadLetters
import akka.actor.DeadLetter
import akka.actor.DeadLetterActorRef
import akka.actor.Dropped
import akka.event.Logging.Info
import akka.util.PrettyDuration._

class DeadLetterListener extends Actor {

  val eventStream: EventStream = context.system.eventStream
  protected val maxCount: Int = context.system.settings.LogDeadLetters
  private val isAlwaysLoggingDeadLetters = maxCount == Int.MaxValue
  protected var count: Int = 0

  override def preStart(): Unit = {
    eventStream.subscribe(self, classOf[DeadLetter])
    eventStream.subscribe(self, classOf[Dropped])
  }

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
    case d: AllDeadLetters =>
      incrementCount()
      logDeadLetter(d, doneMsg = "")
  }

  private def receiveWithMaxCountLogging: Receive = {
    case d: AllDeadLetters =>
      incrementCount()
      if (count == maxCount) {
        logDeadLetter(d, ", no more dead letters will be logged")
        context.stop(self)
      } else {
        logDeadLetter(d, "")
      }
  }

  private def receiveWithSuspendLogging(suspendDuration: FiniteDuration): Receive = {
    case d: AllDeadLetters =>
      incrementCount()
      if (count == maxCount) {
        val doneMsg = s", no more dead letters will be logged in next [${suspendDuration.pretty}]"
        logDeadLetter(d, doneMsg)
        context.become(receiveWhenSuspended(suspendDuration, Deadline.now + suspendDuration))
      } else
        logDeadLetter(d, "")
  }

  private def receiveWhenSuspended(suspendDuration: FiniteDuration, suspendDeadline: Deadline): Receive = {
    case d: AllDeadLetters =>
      incrementCount()
      if (suspendDeadline.isOverdue()) {
        val doneMsg = s", of which ${count - maxCount - 1} were not logged. The counter will be reset now"
        logDeadLetter(d, doneMsg)
        count = 0
        context.become(receiveWithSuspendLogging(suspendDuration))
      }
  }

  private def logDeadLetter(d: AllDeadLetters, doneMsg: String): Unit = {
    val origin = if (isReal(d.sender)) s" from ${d.sender}" else ""
    val logMessage = d match {
      case dropped: Dropped =>
        val destination = if (isReal(d.recipient)) s" to ${d.recipient}" else ""
        s"Message [${d.message.getClass.getName}]$origin$destination was dropped. ${dropped.reason}. " +
        s"[$count] dead letters encountered$doneMsg. "
      case _ =>
        s"Message [${d.message.getClass.getName}]$origin to ${d.recipient} was not delivered. " +
        s"[$count] dead letters encountered$doneMsg. " +
        s"If this is not an expected behavior then ${d.recipient} may have terminated unexpectedly. "
    }
    eventStream.publish(
      Info(
        d.recipient.path.toString,
        d.recipient.getClass,
        logMessage +
        "This logging can be turned off or adjusted with configuration settings 'akka.log-dead-letters' " +
        "and 'akka.log-dead-letters-during-shutdown'."))
  }

  private def isReal(snd: ActorRef): Boolean = {
    (snd ne ActorRef.noSender) && (snd ne context.system.deadLetters) && !snd.isInstanceOf[DeadLetterActorRef]
  }

}
