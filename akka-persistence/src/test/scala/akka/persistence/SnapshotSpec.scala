/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.testkit._

object SnapshotSpec {
  case object TakeSnapshot

  class SaveSnapshotTestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    var state = List.empty[String]
    def receive = {
      case Persistent(payload, snr) ⇒ state = s"${payload}-${snr}" :: state
      case TakeSnapshot             ⇒ saveSnapshot(state)
      case SaveSnapshotSuccess(md)  ⇒ probe ! md.sequenceNr
      case GetState                 ⇒ probe ! state.reverse
    }
  }

  class LoadSnapshotTestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, snr) ⇒ probe ! s"${payload}-${snr}"
      case SnapshotOffer(md, s)     ⇒ probe ! ((md, s))
      case other                    ⇒ probe ! other
    }
    override def preStart() = ()
  }

  case class Delete1(metadata: SnapshotMetadata)
  case class DeleteN(criteria: SnapshotSelectionCriteria)

  class DeleteSnapshotTestProcessor(name: String, probe: ActorRef) extends LoadSnapshotTestProcessor(name, probe) {
    override def receive = receiveDelete orElse super.receive
    def receiveDelete: Receive = {
      case Delete1(metadata) ⇒ deleteSnapshot(metadata.sequenceNr, metadata.timestamp)
      case DeleteN(criteria) ⇒ deleteSnapshots(criteria)
    }
  }
}

class SnapshotSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "SnapshotSpec")) with PersistenceSpec with ImplicitSender {
  import SnapshotSpec._
  import SnapshotProtocol._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = system.actorOf(Props(classOf[SaveSnapshotTestProcessor], name, testActor))
    processor ! Persistent("a")
    processor ! TakeSnapshot
    processor ! Persistent("b")
    processor ! TakeSnapshot
    processor ! Persistent("c")
    processor ! Persistent("d")
    processor ! TakeSnapshot
    processor ! Persistent("e")
    processor ! Persistent("f")
    expectMsgAllOf(1L, 2L, 4L)
  }

  "A processor" must {
    "recover state starting from the most recent snapshot" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      processor ! Recover()

      expectMsgPF() {
        case (SnapshotMetadata(`persistenceId`, 4, timestamp), state) ⇒
          state should be(List("a-1", "b-2", "c-3", "d-4").reverse)
          timestamp should be > (0L)
      }
      expectMsg("e-5")
      expectMsg("f-6")
      expectMsg(RecoveryCompleted)
    }
    "recover state starting from the most recent snapshot matching an upper sequence number bound" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      processor ! Recover(toSequenceNr = 3)

      expectMsgPF() {
        case (SnapshotMetadata(`persistenceId`, 2, timestamp), state) ⇒
          state should be(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg("c-3")
      expectMsg(RecoveryCompleted)
    }
    "recover state starting from the most recent snapshot matching an upper sequence number bound (without further replay)" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      processor ! Recover(toSequenceNr = 4)
      processor ! "done"

      expectMsgPF() {
        case (SnapshotMetadata(`persistenceId`, 4, timestamp), state) ⇒
          state should be(List("a-1", "b-2", "c-3", "d-4").reverse)
          timestamp should be > (0L)
      }
      expectMsg(RecoveryCompleted)
      expectMsg("done")
    }
    "recover state starting from the most recent snapshot matching criteria" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      processor ! Recover(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = 2))

      expectMsgPF() {
        case (SnapshotMetadata(`persistenceId`, 2, timestamp), state) ⇒
          state should be(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg("e-5")
      expectMsg("f-6")
      expectMsg(RecoveryCompleted)
    }
    "recover state starting from the most recent snapshot matching criteria and an upper sequence number bound" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      processor ! Recover(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = 2), toSequenceNr = 3)

      expectMsgPF() {
        case (SnapshotMetadata(`persistenceId`, 2, timestamp), state) ⇒
          state should be(List("a-1", "b-2").reverse)
          timestamp should be > (0L)
      }
      expectMsg("c-3")
      expectMsg(RecoveryCompleted)
    }
    "recover state from scratch if snapshot based recovery is disabled" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))

      processor ! Recover(fromSnapshot = SnapshotSelectionCriteria.None, toSequenceNr = 3)

      expectMsg("a-1")
      expectMsg("b-2")
      expectMsg("c-3")
      expectMsg(RecoveryCompleted)
    }
    "support single message deletions" in {
      val deleteProbe = TestProbe()

      val processor1 = system.actorOf(Props(classOf[DeleteSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      system.eventStream.subscribe(deleteProbe.ref, classOf[DeleteSnapshot])

      // recover processor from 3rd snapshot and then delete snapshot
      processor1 ! Recover(toSequenceNr = 4)
      processor1 ! "done"

      val metadata = expectMsgPF() {
        case (md @ SnapshotMetadata(`persistenceId`, 4, _), state) ⇒
          state should be(List("a-1", "b-2", "c-3", "d-4").reverse)
          md
      }
      expectMsg(RecoveryCompleted)
      expectMsg("done")

      processor1 ! Delete1(metadata)
      deleteProbe.expectMsgType[DeleteSnapshot]

      // recover processor from 2nd snapshot (3rd was deleted) plus replayed messages
      val processor2 = system.actorOf(Props(classOf[DeleteSnapshotTestProcessor], name, testActor))

      processor2 ! Recover(toSequenceNr = 4)
      expectMsgPF() {
        case (md @ SnapshotMetadata(`persistenceId`, 2, _), state) ⇒
          state should be(List("a-1", "b-2").reverse)
          md
      }
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg(RecoveryCompleted)
    }
    "support bulk message deletions" in {
      val deleteProbe = TestProbe()

      val processor1 = system.actorOf(Props(classOf[DeleteSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      system.eventStream.subscribe(deleteProbe.ref, classOf[DeleteSnapshots])

      // recover processor and the delete first three (= all) snapshots
      processor1 ! Recover(toSequenceNr = 4)
      processor1 ! DeleteN(SnapshotSelectionCriteria(maxSequenceNr = 4))
      expectMsgPF() {
        case (md @ SnapshotMetadata(`persistenceId`, 4, _), state) ⇒
          state should be(List("a-1", "b-2", "c-3", "d-4").reverse)
      }
      expectMsg(RecoveryCompleted)
      deleteProbe.expectMsgType[DeleteSnapshots]

      // recover processor from replayed messages (all snapshots deleted)
      val processor2 = system.actorOf(Props(classOf[DeleteSnapshotTestProcessor], name, testActor))

      processor2 ! Recover(toSequenceNr = 4)
      expectMsg("a-1")
      expectMsg("b-2")
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg(RecoveryCompleted)
    }
  }
}
