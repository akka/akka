/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import akka.actor.{ Actor, ActorRef, DeadLetter }
import akka.event.Logging.Info

import scala.concurrent.duration.{ Deadline, Duration, FiniteDuration }

class DeadLetterListener extends Actor {

  val eventStream: EventStream = context.system.eventStream
  protected val maxCount: Int = context.system.settings.LogDeadLetters
  private val isAlwaysLoggingDeadLetters = maxCount == Int.MaxValue
  private val suspendDuration: Duration = context.system.settings.LogDeadLettersSuspendDuration
  protected var count = 0
  private var suspendDeadline: Deadline = Deadline.now

  override def preStart(): Unit =
    eventStream.subscribe(self, classOf[DeadLetter])

  // don't re-subscribe, skip call to preStart
  override def postRestart(reason: Throwable): Unit = ()

  // don't remove subscription, skip call to postStop, no children to stop
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = ()

  override def postStop(): Unit =
    eventStream.unsubscribe(self)

  def receive: Receive = {

    case DeadLetter(message, sender, receipt) ⇒
      if (isAlwaysLoggingDeadLetters) {
        logDeadLetter(message, sender, receipt, doneMsg = "")
      } else {
        suspendDuration match {
          case duration: FiniteDuration ⇒
            if (count == maxCount && suspendDeadline.isOverdue()) {
              count = 1 // reset, and start logging again
            }
            if (count < maxCount) {
              val willDone = count + 1 == maxCount
              if (willDone) {
                logDeadLetter(message, sender, receipt,
                  s", no more dead letters will be logged in next :[$suspendDuration]")
                // after the dead letters were logged maxCount times,
                // dead letters it will suspend logging for suspendDuration.
                suspendDeadline = Deadline.now + duration
              } else {
                logDeadLetter(message, sender, receipt, "")
              }
              count += 1
            }
          case _ ⇒
            //When no suspending
            val willDone = count + 1 == maxCount
            if (willDone) {
              logDeadLetter(message, sender, receipt, ", no more dead letters will be logged")
              context.stop(self)
            } else {
              logDeadLetter(message, sender, receipt, "")
              count += 1
            }
        }
      }
  }

  private def logDeadLetter(message: Any, sender: ActorRef, receipt: ActorRef, doneMsg: String): Unit = {
    val origin = if (sender eq context.system.deadLetters) "without sender" else s"from $sender"

    eventStream.publish(Info(receipt.path.toString, receipt.getClass,
      s"Message [${message.getClass.getName}] $origin to $receipt was not delivered. [$count] dead letters encountered$doneMsg. " +
        s"If this is not an expected behavior, the receiver may have terminated unexpectedly, " +
        "This logging can be turned off or adjusted with configuration settings 'akka.log-dead-letters' " +
        "and 'akka.log-dead-letters-during-shutdown'."))
  }

}
