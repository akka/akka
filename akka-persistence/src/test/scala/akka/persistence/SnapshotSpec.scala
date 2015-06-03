/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.testkit._

object SnapshotSpec {
  case object TakeSnapshot

  class SaveSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    var state = List.empty[String]

    override def receiveRecover: Receive = {
      case payload: String                     ⇒ state = s"${payload}-${lastSequenceNr}" :: state
      case SnapshotOffer(_, snapshot: List[_]) ⇒ state = snapshot.asInstanceOf[List[String]]
    }

    override def receiveCommand = {
      case payload: String ⇒
        persist(payload) { _ ⇒
          state = s"${payload}-${lastSequenceNr}" :: state
        }
      case TakeSnapshot            ⇒ saveSnapshot(state)
      case SaveSnapshotSuccess(md) ⇒ probe ! md.sequenceNr
      case GetState                ⇒ probe ! state.reverse
    }
  }

  class LoadSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    override def receiveRecover: Receive = {
      case payload: String              ⇒ probe ! s"${payload}-${lastSequenceNr}"
      case offer @ SnapshotOffer(md, s) ⇒ probe ! offer
      case other                        ⇒ probe ! other
    }

    override def receiveCommand = {
      case "done" ⇒ probe ! "done"
      case payload: String ⇒
        persist(payload) { _ ⇒
          probe ! s"${payload}-${lastSequenceNr}"
        }
      case offer @ SnapshotOffer(md, s) ⇒ probe ! offer
      case other                        ⇒ probe ! other
    }
    override def preStart() = ()
  }

  final case class Delete1(metadata: SnapshotMetadata)
  final case class DeleteN(criteria: SnapshotSelectionCriteria)

  class DeleteSnapshotTestPersistentActor(name: String, probe: ActorRef) extends LoadSnapshotTestPersistentActor(name, probe) {
    override def receiveCommand = receiveDelete orElse super.receiveCommand
    def receiveDelete: Receive = {
      case Delete1(metadata) ⇒ deleteSnapshot(metadata.sequenceNr)
      case DeleteN(criteria) ⇒ deleteSnapshots(criteria)
    }
  }
}

class SnapshotSpec extends PersistenceSpec(PersistenceSpec.config("leveldb", "SnapshotSpec")) with ImplicitSender {
  import SnapshotSpec._
  import SnapshotProtocol._

  override protected def beforeEach() {
    super.beforeEach()

    val persistentActor = system.actorOf(Props(classOf[SaveSnapshotTestPersistentActor], name, testActor))
    persistentActor ! "a"
    persistentActor ! TakeSnapshot
    persistentActor ! "b"
    persistentActor ! TakeSnapshot
    persistentActor ! "c"
    persistentActor ! "d"
    persistentActor ! TakeSnapshot
    persistentActor ! "e"
    persistentActor ! "f"
    expectMsgAllOf(1L, 2L, 4L)
  }

  "A persistentActor" must {
    "recover state starting from the most recent snapshot" in {
      val persistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      persistentActor ! Recover()

      expectMsgPF() {
        case SnapshotOffer(SnapshotMetadata(`persistenceId`, 4, timestamp), state) ⇒
          state should ===(List("a-1", "b-2", "c-3", "d-4").reverse)
          timestamp should be > (0L)
      }
      expectMsg("e-5")
      expectMsg("f-6")
      expectMsg(RecoveryCompleted)
    }
    "recover state starting from the most recent snapshot matching an upper sequence number bound" in {
      val persistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      persistentActor ! Recover(toSequenceNr = 3)

      expectMsgPF() {
        case SnapshotOffer(SnapshotMetadata(`persistenceId`, 2, timestamp), state) ⇒
          state should ===(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg("c-3")
      expectMsg(RecoveryCompleted)
    }
    "recover state starting from the most recent snapshot matching an upper sequence number bound (without further replay)" in {
      val persistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      persistentActor ! Recover(toSequenceNr = 4)
      persistentActor ! "done"

      expectMsgPF() {
        case SnapshotOffer(SnapshotMetadata(`persistenceId`, 4, timestamp), state) ⇒
          state should ===(List("a-1", "b-2", "c-3", "d-4").reverse)
          timestamp should be > (0L)
      }
      expectMsg(RecoveryCompleted)
      expectMsg("done")
    }
    "recover state starting from the most recent snapshot matching criteria" in {
      val persistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      persistentActor ! Recover(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = 2))

      expectMsgPF() {
        case SnapshotOffer(SnapshotMetadata(`persistenceId`, 2, timestamp), state) ⇒
          state should ===(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg("e-5")
      expectMsg("f-6")
      expectMsg(RecoveryCompleted)
    }
    "recover state starting from the most recent snapshot matching criteria and an upper sequence number bound" in {
      val persistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      persistentActor ! Recover(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = 2), toSequenceNr = 3)

      expectMsgPF() {
        case SnapshotOffer(SnapshotMetadata(`persistenceId`, 2, timestamp), state) ⇒
          state should ===(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg("c-3")
      expectMsg(RecoveryCompleted)
    }
    "recover state from scratch if snapshot based recovery is disabled" in {
      val persistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))

      persistentActor ! Recover(fromSnapshot = SnapshotSelectionCriteria.None, toSequenceNr = 3)

      expectMsg("a-1")
      expectMsg("b-2")
      expectMsg("c-3")
      expectMsg(RecoveryCompleted)
    }
    "support single snapshot deletions" in {
      val deleteProbe = TestProbe()

      val persistentActor1 = system.actorOf(Props(classOf[DeleteSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      system.eventStream.subscribe(deleteProbe.ref, classOf[DeleteSnapshot])

      // recover persistentActor from 3rd snapshot and then delete snapshot
      persistentActor1 ! Recover(toSequenceNr = 4)
      persistentActor1 ! "done"

      val metadata = expectMsgPF(hint = "" + SnapshotOffer(SnapshotMetadata(persistenceId, 4, 0), null)) {
        case SnapshotOffer(md @ SnapshotMetadata(`persistenceId`, 4, _), state) ⇒
          state should ===(List("a-1", "b-2", "c-3", "d-4").reverse)
          md
      }
      expectMsg(RecoveryCompleted)
      expectMsg("done")

      persistentActor1 ! Delete1(metadata)
      deleteProbe.expectMsgType[DeleteSnapshot]
      expectMsgPF(hint = "" + DeleteSnapshotSuccess(SnapshotMetadata(`persistenceId`, 4, 0))) {
        case m @ DeleteSnapshotSuccess(SnapshotMetadata(`persistenceId`, 4, _)) ⇒
          info("success = " + m)
      }

      // recover persistentActor from 2nd snapshot (3rd was deleted) plus replayed messages
      val persistentActor2 = system.actorOf(Props(classOf[DeleteSnapshotTestPersistentActor], name, testActor))

      persistentActor2 ! Recover(toSequenceNr = 4)
      expectMsgPF(hint = "" + SnapshotOffer(SnapshotMetadata(`persistenceId`, 2, 0), null)) {
        case SnapshotOffer(md @ SnapshotMetadata(`persistenceId`, 2, _), state) ⇒
          state should ===(List("a-1", "b-2").reverse)
          md
      }
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg(RecoveryCompleted)
    }
    "support bulk snapshot deletions" in {
      val deleteProbe = TestProbe()

      val persistentActor1 = system.actorOf(Props(classOf[DeleteSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      system.eventStream.subscribe(deleteProbe.ref, classOf[DeleteSnapshots])

      // recover persistentActor and the delete first three (= all) snapshots
      persistentActor1 ! Recover(toSequenceNr = 4)
      val criteria = SnapshotSelectionCriteria(maxSequenceNr = 4)
      persistentActor1 ! DeleteN(criteria)
      expectMsgPF() {
        case SnapshotOffer(md @ SnapshotMetadata(`persistenceId`, 4, _), state) ⇒
          state should ===(List("a-1", "b-2", "c-3", "d-4").reverse)
      }
      expectMsg(RecoveryCompleted)
      deleteProbe.expectMsgType[DeleteSnapshots]
      expectMsgPF() { case DeleteSnapshotsSuccess(`criteria`) ⇒ }

      // recover persistentActor from replayed messages (all snapshots deleted)
      val persistentActor2 = system.actorOf(Props(classOf[DeleteSnapshotTestPersistentActor], name, testActor))

      persistentActor2 ! Recover(toSequenceNr = 4)
      expectMsg("a-1")
      expectMsg("b-2")
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg(RecoveryCompleted)
    }
  }
}
