/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import ReceiveTimeout.emptyReceiveTimeoutData
import akka.actor.ActorCell
import akka.actor.ActorCell.emptyCancellable
import akka.actor.Cancellable
import akka.util.Duration

object ReceiveTimeout {
  final val emptyReceiveTimeoutData: (Duration, Cancellable) = (Duration.Undefined, ActorCell.emptyCancellable)
}

trait ReceiveTimeout { this: ActorCell ⇒

  import ReceiveTimeout._
  import ActorCell._

  private var receiveTimeoutData: (Duration, Cancellable) = emptyReceiveTimeoutData

  final def receiveTimeout: Option[Duration] = receiveTimeoutData._1 match {
    case Duration.Undefined ⇒ None
    case duration           ⇒ Some(duration)
  }

  final def setReceiveTimeout(timeout: Option[Duration]): Unit = setReceiveTimeout(timeout.getOrElse(Duration.Undefined))

  final def setReceiveTimeout(timeout: Duration): Unit =
    receiveTimeoutData = (
      if (Duration.Undefined == timeout || timeout.toMillis < 1) Duration.Undefined else timeout,
      receiveTimeoutData._2)

  final def resetReceiveTimeout(): Unit = setReceiveTimeout(None)

  final def checkReceiveTimeout() {
    val recvtimeout = receiveTimeoutData
    if (Duration.Undefined != recvtimeout._1 && !mailbox.hasMessages) {
      recvtimeout._2.cancel() //Cancel any ongoing future
      //Only reschedule if desired and there are currently no more messages to be processed
      receiveTimeoutData = (recvtimeout._1, system.scheduler.scheduleOnce(recvtimeout._1, self, akka.actor.ReceiveTimeout))
    } else cancelReceiveTimeout()

  }

  final def cancelReceiveTimeout(): Unit =
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }

}