/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor.{ Props, ActorRef }
import akka.testkit.{ TestEvent, EventFilter, ImplicitSender, AkkaSpec }
import scala.concurrent.duration._
import akka.persistence.snapshot.local.LocalSnapshotStore
import akka.persistence.serialization.Snapshot
import akka.event.Logging

import scala.language.postfixOps

object SnapshotFailureRobustnessSpec {

  case class Cmd(payload: String)

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
    override def preStart() = ()
  }

  class FailingLocalSnapshotStore extends LocalSnapshotStore {
    override def save(metadata: SnapshotMetadata, snapshot: Any): Unit = {
      if (metadata.sequenceNr == 2) {
        val bytes = "b0rk".getBytes("UTF-8")
        withOutputStream(metadata)(_.write(bytes))
      } else super.save(metadata, snapshot)
    }
  }
}

class SnapshotFailureRobustnessSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "SnapshotFailureRobustnessSpec", serialization = "off", extraConfig = Some(
  """
  akka.persistence.snapshot-store.local.class = "akka.persistence.SnapshotFailureRobustnessSpec$FailingLocalSnapshotStore"
  """))) with PersistenceSpec with ImplicitSender {

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
        lPersistentActor ! Recover()
        expectMsgPF() {
          case (SnapshotMetadata(`persistenceId`, 1, timestamp), state) ⇒
            state should be("blahonga")
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
  }
}
