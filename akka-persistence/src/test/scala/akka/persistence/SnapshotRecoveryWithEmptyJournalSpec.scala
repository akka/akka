/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence

import akka.actor._
import akka.testkit._

object SnapshotRecoveryWithEmptyJournalSpec {
  case class TakeSnapshot(deleteMessages: Boolean)

  class SaveSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    var deleteMessages = false
    var state = List.empty[String]

    override def receiveRecover: Receive = {
      case payload: String                     => state = s"${payload}-${lastSequenceNr}" :: state
      case SnapshotOffer(_, snapshot: List[_]) => state = snapshot.asInstanceOf[List[String]]
    }

    override def receiveCommand = {
      case payload: String =>
        persist(payload) { _ =>
          state = s"${payload}-${lastSequenceNr}" :: state
        }
      case TakeSnapshot(deleteMsgs) =>
        deleteMessages = deleteMsgs
        saveSnapshot(state)
      case SaveSnapshotSuccess(md) =>
        if (deleteMessages) {
          deleteMessages(md.sequenceNr)
          deleteMessages = false
        }
        probe ! md.sequenceNr
      case GetState                 => probe ! state.reverse
      case o: DeleteMessagesSuccess => probe ! o
    }
  }

  class LoadSnapshotTestPersistentActor(name: String, _recovery: Recovery, probe: ActorRef)
    extends NamedPersistentActor(name) {
    override def recovery: Recovery = _recovery

    override def receiveRecover: Receive = {
      case payload: String      => probe ! s"${payload}-${lastSequenceNr}"
      case offer: SnapshotOffer => probe ! offer
      case other                => probe ! other
    }

    override def receiveCommand = {
      case "done" => probe ! "done"
      case payload: String =>
        persist(payload) { _ =>
          probe ! s"${payload}-${lastSequenceNr}"
        }
      case offer: SnapshotOffer => probe ! offer
      case other                => probe ! other
    }
  }
}

class SnapshotRecoveryWithEmptyJournalSpec extends PersistenceSpec(PersistenceSpec.config(
  "inmem", // with leveldb the error cannot be reproduced (counterKey is not reset after deleteMessages() )
  "SnapshotRecoveryWithEmptyJournalSpec")) with ImplicitSender {
  import SnapshotRecoveryWithEmptyJournalSpec._

  "A persistentActor" must {
    "recover state starting from the most recent snapshot and use subsequent sequence numbers to persist events to the journal" in {
      val persistenceId = name
      val persistentActor = system.actorOf(Props(classOf[SaveSnapshotTestPersistentActor], name, testActor))
      persistentActor ! "a"
      persistentActor ! "b"
      persistentActor ! TakeSnapshot(true)
      expectMsgAllOf(DeleteMessagesSuccess(2), 2L)

      system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, Recovery(), testActor))
      expectMsgPF() {
        case SnapshotOffer(SnapshotMetadata(`persistenceId`, 2, timestamp), state) =>
          state should ===(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg(RecoveryCompleted)

      val persistentActor1 = system.actorOf(Props(classOf[SaveSnapshotTestPersistentActor], name, testActor))
      persistentActor1 ! "c"
      persistentActor1 ! TakeSnapshot(true)
      expectMsgAllOf(DeleteMessagesSuccess(3), 3L)
    }
  }
}
