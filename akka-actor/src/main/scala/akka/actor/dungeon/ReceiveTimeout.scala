/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.dungeon

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorCell
import akka.actor.Cancellable
import akka.actor.NotInfluenceReceiveTimeout

private[akka] object ReceiveTimeout {
  final val emptyReceiveTimeoutData: (Duration, Cancellable) = (Duration.Undefined, ActorCell.emptyCancellable)
}

private[akka] trait ReceiveTimeout { this: ActorCell =>

  import ReceiveTimeout._
  import ActorCell._

  private var receiveTimeoutData: (Duration, Cancellable) = emptyReceiveTimeoutData

  final def receiveTimeout: Duration = receiveTimeoutData._1

  final def setReceiveTimeout(timeout: Duration): Unit = receiveTimeoutData = receiveTimeoutData.copy(_1 = timeout)

  /** Called after `ActorCell.receiveMessage` or `ActorCell.autoReceiveMessage`. */
  protected def checkReceiveTimeoutIfNeeded(message: Any, beforeReceive: (Duration, Cancellable)): Unit =
    if (hasTimeoutData || receiveTimeoutChanged(beforeReceive))
      checkReceiveTimeout(!message.isInstanceOf[NotInfluenceReceiveTimeout] || receiveTimeoutChanged(beforeReceive))

  final def checkReceiveTimeout(reschedule: Boolean = true): Unit = {
    val (recvTimeout, task) = receiveTimeoutData
    recvTimeout match {
      case f: FiniteDuration =>
        // The fact that timeout is FiniteDuration and task is emptyCancellable
        // means that a user called `context.setReceiveTimeout(...)`
        // while sending the ReceiveTimeout message is not scheduled yet.
        // We have to handle the case and schedule sending the ReceiveTimeout message
        // ignoring the reschedule parameter.
        if (reschedule || (task eq emptyCancellable))
          rescheduleReceiveTimeout(f)

      case _ => cancelReceiveTimeout()
    }
  }

  private def rescheduleReceiveTimeout(f: FiniteDuration): Unit = {
    receiveTimeoutData._2.cancel() //Cancel any ongoing future
    val task = system.scheduler.scheduleOnce(f, self, akka.actor.ReceiveTimeout)(this.dispatcher)
    receiveTimeoutData = (f, task)
  }

  private def hasTimeoutData: Boolean = receiveTimeoutData ne emptyReceiveTimeoutData

  private def receiveTimeoutChanged(beforeReceive: (Duration, Cancellable)): Boolean =
    receiveTimeoutData ne beforeReceive

  protected def cancelReceiveTimeoutIfNeeded(message: Any): (Duration, Cancellable) = {
    if (hasTimeoutData && !message.isInstanceOf[NotInfluenceReceiveTimeout])
      cancelReceiveTimeout()

    receiveTimeoutData
  }

  override final def cancelReceiveTimeout(): Unit =
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }

}
