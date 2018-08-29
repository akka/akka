/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal

import akka.actor.{ActorRef, Props}
import akka.persistence.{DeleteMessagesSuccess, PersistentActor}

import scala.collection.mutable

object TestActor {
  def props(persistenceId: String): Props =
    Props(new TestActor(persistenceId))

  case class DeleteCmd(toSeqNr: Long = Long.MaxValue)
}

class TestActor(override val persistenceId: String) extends PersistentActor {

  import TestActor.DeleteCmd

  val receiveRecover: Receive = {
    case evt: String ⇒
  }

  private val _deleteRequesters: mutable.Buffer[ActorRef] = mutable.Buffer.empty

  val receiveCommand: Receive = {
    case DeleteCmd(toSeqNr) ⇒
      _deleteRequesters.append(sender())
      deleteMessages(toSeqNr)

    case DeleteMessagesSuccess(toSeqNr) ⇒
      val senders = _deleteRequesters.toArray
      _deleteRequesters.clear()

      senders.foreach(_ ! s"$toSeqNr-deleted")

    case cmd: String ⇒
      persist(cmd) { evt ⇒
        sender() ! evt + "-done"
      }
  }

}
