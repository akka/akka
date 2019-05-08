/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon

import akka.actor.ActorCell
import akka.actor.Cancellable
import akka.actor.NotInfluenceReceiveTimeout

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

private[akka] object ReceiveTimeout {
  import ActorCell._

  private final val empty: ReceiveTimeoutValue =
    ReceiveTimeoutValue(Duration.Undefined, emptyCancellable, Duration.Undefined)

  final case class ReceiveTimeoutValue(current: Duration, task: Cancellable, previous: Duration)

  object ReceiveTimeoutValue {
    def apply(): ReceiveTimeoutValue = empty
  }
}

private[akka] trait ReceiveTimeout { this: ActorCell =>

  import ReceiveTimeout._
  import ActorCell._

  private var value: ReceiveTimeoutValue = ReceiveTimeoutValue()

  final def receiveTimeout: Duration = value.current

  final def setReceiveTimeout(timeout: Duration): Unit = {
    val prev = value.current
    value = value.copy(current = timeout, previous = prev)
  }

  protected def checkReceiveTimeoutIfNeeded(message: Any): Unit =
    if (hasTimeoutData)
      checkReceiveTimeout(!message.isInstanceOf[NotInfluenceReceiveTimeout] || (value.current ne value.previous))

  final def checkReceiveTimeout(reschedule: Boolean = true): Unit =
    value.current match {
      case f: FiniteDuration =>
        // The fact that timeout is FiniteDuration and task is emptyCancellable
        // means that a user called `context.setReceiveTimeout(...)`
        // while sending the ReceiveTimeout message is not scheduled yet.
        // We have to handle the case and schedule sending the ReceiveTimeout message
        // ignoring the reschedule parameter.
        if (reschedule || (value.task eq emptyCancellable))
          rescheduleReceiveTimeout(f)

      case _ => cancelReceiveTimeout()
    }

  private def rescheduleReceiveTimeout(f: FiniteDuration): Unit = {
    value.task.cancel() //Cancel any ongoing future
    val t = system.scheduler.scheduleOnce(f, self, akka.actor.ReceiveTimeout)(this.dispatcher)
    value = value.copy(current = f, task = t) // keep previous
  }

  private def hasTimeoutData: Boolean = value ne empty

  protected def cancelReceiveTimeoutIfNeeded(message: Any): Unit =
    if (hasTimeoutData && !message.isInstanceOf[NotInfluenceReceiveTimeout])
      cancelReceiveTimeout()

  override final def cancelReceiveTimeout(): Unit =
    if (value.task ne emptyCancellable) {
      value.task.cancel()
      value = value.copy(task = emptyCancellable)
    }
}
