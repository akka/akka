/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dungeon

import ReceiveTimeout.emptyReceiveTimeoutData
import akka.actor.ActorCell
import akka.actor.ActorCell.emptyCancellable
import akka.actor.Cancellable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

private[akka] object ReceiveTimeout {
  final val emptyReceiveTimeoutData: (Duration, Cancellable) = (Duration.Undefined, ActorCell.emptyCancellable)
}

private[akka] trait ReceiveTimeout { this: ActorCell ⇒

  import ReceiveTimeout._
  import ActorCell._

  private var receiveTimeoutData: (Duration, Cancellable) = emptyReceiveTimeoutData

  final def receiveTimeout: Duration = receiveTimeoutData._1

  final def setReceiveTimeout(timeout: Duration): Unit = receiveTimeoutData = receiveTimeoutData.copy(_1 = timeout)

  final def checkReceiveTimeout() {
    val recvtimeout = receiveTimeoutData
    //Only reschedule if desired and there are currently no more messages to be processed
    if (!mailbox.hasMessages) recvtimeout._1 match {
      case f: FiniteDuration ⇒
        recvtimeout._2.cancel() //Cancel any ongoing future
        val task = system.scheduler.scheduleOnce(f, self, akka.actor.ReceiveTimeout)(this.dispatcher)
        receiveTimeoutData = (f, task)
      case _ ⇒ cancelReceiveTimeout()
    }
    else cancelReceiveTimeout()

  }

  final def cancelReceiveTimeout(): Unit =
    if (receiveTimeoutData._2 ne emptyCancellable) {
      receiveTimeoutData._2.cancel()
      receiveTimeoutData = (receiveTimeoutData._1, emptyCancellable)
    }

}