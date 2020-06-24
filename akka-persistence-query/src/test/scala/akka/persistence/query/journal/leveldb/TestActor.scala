/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.actor.Props
import akka.persistence.PersistentActor
import akka.persistence.journal.EventWithMetaData
import akka.persistence.query.journal.leveldb.TestActor.WithMeta

object TestActor {
  def props(persistenceId: String): Props =
    Props(new TestActor(persistenceId))

  case class DeleteCmd(toSeqNr: Long = Long.MaxValue)

  case class WithMeta(payload: String, meta: Any)
}

class TestActor(override val persistenceId: String) extends PersistentActor {

  import TestActor.DeleteCmd

  val receiveRecover: Receive = {
    case _: String =>
  }

  val receiveCommand: Receive = {
    case DeleteCmd(toSeqNr) =>
      deleteMessages(toSeqNr)
      sender() ! s"$toSeqNr-deleted"
    case WithMeta(payload, meta) =>
      persist(EventWithMetaData(payload, meta)) { _ =>
        sender() ! s"$payload-done"
      }

    case cmd: String =>
      persist(cmd) { evt =>
        sender() ! s"$evt-done"
      }
  }

}
