/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.leveldb

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.persistence._
import akka.testkit.{ TestProbe, AkkaSpec }

object SharedLeveldbJournalSpec {
  val config =
    """
      akka {
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        persistence {
          journal {
            plugin = "akka.persistence.journal.leveldb-shared"
            leveldb-shared.store.dir = target/journal-SharedLeveldbJournalSpec
          }
          snapshot-store.local.dir = target/snapshots-SharedLeveldbJournalSpec
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
          netty.tcp {
            hostname = "127.0.0.1"
            port = 0
          }
        }
        loglevel = ERROR
        log-dead-letters = 0
        log-dead-letters-during-shutdown = off
        test.single-expect-default = 10s
      }
    """

  class ExampleProcessor(probe: ActorRef, name: String) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, _) ⇒ probe ! payload
    }
  }

  class ExampleApp(probe: ActorRef, storePath: ActorPath) extends Actor {
    val processor = context.actorOf(Props(classOf[ExampleProcessor], probe, context.system.name))

    def receive = {
      case ActorIdentity(1, Some(store)) ⇒ SharedLeveldbJournal.setStore(store, context.system)
      case m                             ⇒ processor forward m
    }

    override def preStart(): Unit = {
      context.actorSelection(storePath) ! Identify(1)
    }
  }

}

class SharedLeveldbJournalSpec extends AkkaSpec(SharedLeveldbJournalSpec.config) with Cleanup {
  import SharedLeveldbJournalSpec._

  val processorASystem = ActorSystem("processorA", system.settings.config)
  val processorBSystem = ActorSystem("processorB", system.settings.config)

  override protected def afterTermination() {
    shutdown(processorASystem)
    shutdown(processorBSystem)
    super.afterTermination()
  }

  "A LevelDB store" can {
    "be shared by multiple actor systems" in {

      val processorAProbe = new TestProbe(processorASystem)
      val processorBProbe = new TestProbe(processorBSystem)

      system.actorOf(Props[SharedLeveldbStore], "store")
      val storePath = RootActorPath(system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress) / "user" / "store"

      val appA = processorASystem.actorOf(Props(classOf[ExampleApp], processorAProbe.ref, storePath))
      val appB = processorBSystem.actorOf(Props(classOf[ExampleApp], processorBProbe.ref, storePath))

      appA ! Persistent("a1")
      appB ! Persistent("b1")

      processorAProbe.expectMsg("a1")
      processorBProbe.expectMsg("b1")

      val recoveredAppA = processorASystem.actorOf(Props(classOf[ExampleApp], processorAProbe.ref, storePath))
      val recoveredAppB = processorBSystem.actorOf(Props(classOf[ExampleApp], processorBProbe.ref, storePath))

      recoveredAppA ! Persistent("a2")
      recoveredAppB ! Persistent("b2")

      processorAProbe.expectMsg("a1")
      processorAProbe.expectMsg("a2")

      processorBProbe.expectMsg("b1")
      processorBProbe.expectMsg("b2")
    }
  }
}
