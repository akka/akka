/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import java.io.IOException

import akka.actor.{ ActorRef, Props }
import akka.event.Logging
import akka.persistence.snapshot.local.LocalSnapshotStore
import akka.testkit.{ EventFilter, ImplicitSender, TestEvent }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object SnapshotFailureRobustnessSpec {

  case class Cmd(payload: String)
  case class DeleteSnapshot(seqNr: Int)
  case class DeleteSnapshots(criteria: SnapshotSelectionCriteria)

  class SaveSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    override def receiveRecover: Receive = {
      case SnapshotOffer(md, s) ⇒ probe ! ((md, s))
      case other                ⇒ probe ! other
    }

    override def receiveCommand = {
      case Cmd(payload)            ⇒ persist(payload)(_ ⇒ saveSnapshot(payload))
      case SaveSnapshotSuccess(md) ⇒ probe ! md.sequenceNr
      case other                   ⇒ probe ! other
    }
  }

  class DeleteSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {

    // TODO do we call it "snapshot store" or "snapshot plugin", small inconsistency here
    override def snapshotPluginId: String =
      "akka.persistence.snapshot-store.local-delete-fail"

    override def receiveRecover: Receive = {
      case SnapshotOffer(md, s) ⇒ probe ! ((md, s))
      case other                ⇒ probe ! other
    }

    override def receiveCommand = {
      case Cmd(payload)            ⇒ persist(payload)(_ ⇒ saveSnapshot(payload))
      case DeleteSnapshot(seqNr)   ⇒ deleteSnapshot(seqNr)
      case DeleteSnapshots(crit)   ⇒ deleteSnapshots(crit)
      case SaveSnapshotSuccess(md) ⇒ probe ! md.sequenceNr
      case other                   ⇒ probe ! other
    }
  }

  class LoadSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    override def receiveRecover: Receive = {
      case SnapshotOffer(md, s) ⇒ probe ! ((md, s))
      case payload: String      ⇒ probe ! s"${payload}-${lastSequenceNr}"
      case other                ⇒ probe ! other
    }

    override def receiveCommand = {
      case Cmd(payload) ⇒
        persist(payload) { _ ⇒
          probe ! s"${payload}-${lastSequenceNr}"
        }
      case SnapshotOffer(md, s) ⇒ probe ! ((md, s))
      case other                ⇒ probe ! other
    }
  }

  class FailingLocalSnapshotStore extends LocalSnapshotStore {
    override def save(metadata: SnapshotMetadata, snapshot: Any): Unit = {
      if (metadata.sequenceNr == 2) {
        val bytes = "b0rk".getBytes("UTF-8")
        withOutputStream(metadata)(_.write(bytes))
      } else super.save(metadata, snapshot)
    }
  }

  class DeleteFailingLocalSnapshotStore extends LocalSnapshotStore {
    override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
      super.deleteAsync(metadata) // we actually delete it properly, but act as if it failed
      Future.failed(new IOException("Failed to delete snapshot for some reason!"))
    }

    override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
      super.deleteAsync(persistenceId, criteria) // we actually delete it properly, but act as if it failed
      Future.failed(new IOException("Failed to delete snapshot for some reason!"))
    }
  }
}

class SnapshotFailureRobustnessSpec extends PersistenceSpec(PersistenceSpec.config("leveldb", "SnapshotFailureRobustnessSpec", serialization = "off", extraConfig = Some(
  """
  akka.persistence.snapshot-store.local.class = "akka.persistence.SnapshotFailureRobustnessSpec$FailingLocalSnapshotStore"
  akka.persistence.snapshot-store.local-delete-fail.class = "akka.persistence.SnapshotFailureRobustnessSpec$DeleteFailingLocalSnapshotStore"
  """))) with ImplicitSender {

  import SnapshotFailureRobustnessSpec._

  "A persistentActor with a failing snapshot" must {
    "recover state starting from the most recent complete snapshot" in {
      val sPersistentActor = system.actorOf(Props(classOf[SaveSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      expectMsg(RecoveryCompleted)
      sPersistentActor ! Cmd("blahonga")
      expectMsg(1)
      sPersistentActor ! Cmd("kablama")
      expectMsg(2)
      system.eventStream.publish(TestEvent.Mute(
        EventFilter.error(start = "Error loading snapshot [")))
      system.eventStream.subscribe(testActor, classOf[Logging.Error])
      try {
        val lPersistentActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], name, testActor))
        expectMsgPF() {
          case (SnapshotMetadata(`persistenceId`, 1, timestamp), state) ⇒
            state should ===("blahonga")
            timestamp should be > (0L)
        }
        expectMsg("kablama-2")
        expectMsg(RecoveryCompleted)
        expectNoMsg(1 second)
      } finally {
        system.eventStream.unsubscribe(testActor, classOf[Logging.Error])
        system.eventStream.publish(TestEvent.UnMute(
          EventFilter.error(start = "Error loading snapshot [")))
      }
    }

    "receive failure message when deleting a single snapshot fails" in {
      val p = system.actorOf(Props(classOf[DeleteSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      expectMsg(RecoveryCompleted)
      p ! Cmd("hello")
      expectMsg(1)
      p ! DeleteSnapshot(1)
      expectMsgPF() {
        case DeleteSnapshotFailure(SnapshotMetadata(`persistenceId`, 1, timestamp), cause) ⇒
          // ok, expected failure
          cause.getMessage should include("Failed to delete")
      }
    }
    "receive failure message when bulk deleting snapshot fails" in {
      val p = system.actorOf(Props(classOf[DeleteSnapshotTestPersistentActor], name, testActor))
      val persistenceId = name

      expectMsg(RecoveryCompleted)
      p ! Cmd("hello")
      expectMsg(1)
      p ! Cmd("hola")
      expectMsg(2)
      val criteria = SnapshotSelectionCriteria(maxSequenceNr = 10)
      p ! DeleteSnapshots(criteria)
      expectMsgPF() {
        case DeleteSnapshotsFailure(criteria, cause) ⇒
          // ok, expected failure
          cause.getMessage should include("Failed to delete")
      }
    }
  }
}
