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

  class SaveSnapshotTestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, snr) ⇒ saveSnapshot(payload)
      case SaveSnapshotSuccess(md)  ⇒ probe ! md.sequenceNr
      case SnapshotOffer(md, s)     ⇒ probe ! ((md, s))
      case other                    ⇒ probe ! other
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
    |akka.persistence.snapshot-store.local.class = "akka.persistence.SnapshotFailureRobustnessSpec$FailingLocalSnapshotStore"
  """.stripMargin))) with PersistenceSpec with ImplicitSender {

  import SnapshotFailureRobustnessSpec._

  "A processor with a failing snapshot" must {
    "recover state starting from the most recent complete snapshot" in {
      val sProcessor = system.actorOf(Props(classOf[SaveSnapshotTestProcessor], name, testActor))
      val persistenceId = name

      expectMsg(RecoveryCompleted)
      sProcessor ! Persistent("blahonga")
      expectMsg(1)
      sProcessor ! Persistent("kablama")
      expectMsg(2)
      system.eventStream.publish(TestEvent.Mute(
        EventFilter.error(start = "Error loading snapshot [")))
      system.eventStream.subscribe(testActor, classOf[Logging.Error])
      try {
        val lProcessor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
        lProcessor ! Recover()
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
