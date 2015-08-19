/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.journal.leveldb

import akka.persistence.PersistentActor
import akka.actor.Props

object TestActor {
  def props(persistenceId: String): Props =
    Props(new TestActor(persistenceId))
}

class TestActor(override val persistenceId: String) extends PersistentActor {

  val receiveRecover: Receive = {
    case evt: String ⇒
  }

  val receiveCommand: Receive = {
    case cmd: String ⇒
      persist(cmd) { evt ⇒
        sender() ! evt + "-done"
      }
  }

}
