/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import ReceiveTimeout.emptyReceiveTimeoutData
import akka.actor.ActorCell
import akka.actor.ActorCell.emptyCancellable
import akka.actor.Cancellable
import scala.concurrent.util.Duration
import scala.concurrent.util.FiniteDuration

private[akka] object ReceiveTimeout {
  final val emptyReceiveTimeoutData: (Option[FiniteDuration], Cancellable) = (None, ActorCell.emptyCancellable)
}

private[akka] trait ReceiveTimeout { this: ActorCell ⇒

  import ReceiveTimeout._
  import ActorCell._

  private var receiveTimeoutData: (Option[FiniteDuration], Cancellable) = emptyReceiveTimeoutData

  final def receiveTimeout: Option[FiniteDuration] = receiveTimeoutData._1

  final def setReceiveTimeout(timeout: Option[FiniteDuration]): Unit =
    receiveTimeoutData = receiveTimeoutData.copy(_1 = timeout)

  final def setReceiveTimeout(timeout: Duration): Unit = {
    import Duration._
    setReceiveTimeout(timeout match {
      case x if x eq Undefined ⇒ None
      case Inf | MinusInf      ⇒ throw new IllegalArgumentException("receiveTimeout cannot be infinite")
      case f: FiniteDuration ⇒
        if (f < Zero) throw new IllegalArgumentException("receiveTimeout cannot be negative")
        else Some(f)
    })
  }

  final def resetReceiveTimeout(): Unit = setReceiveTimeout(None)

  final def checkReceiveTimeout() {
    val recvtimeout = receiveTimeoutData
    if (recvtimeout._1.isDefined && !mailbox.hasMessages) {
      recvtimeout._2.cancel() //Cancel any ongoing future
      //Only reschedule if desired and there are currently no more messages to be processed
      receiveTimeoutData = (recvtimeout._1, system.scheduler.scheduleOnce(recvtimeout._1.get, self, akka.actor.ReceiveTimeout)(this.dispatcher))
    } else cancelReceiveTimeout()

  }

  final def cancelReceiveTimeout(): Unit =
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }

}