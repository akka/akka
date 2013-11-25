/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.leveldb

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
            leveldb-shared.store.dir = target/shared-journal
          }
          snapshot-store.local.dir = target/snapshot-store
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
      }
    """

  class ExampleProcessor(probe: ActorRef, name: String) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, _) ⇒ probe ! payload
    }
  }

  class ExampleApp(probe: ActorRef, port: Int) extends Actor {
    val processor = context.actorOf(Props(classOf[ExampleProcessor], probe, context.system.name))

    def receive = {
      case ActorIdentity(1, Some(store)) ⇒ SharedLeveldbJournal.setStore(store, context.system)
      case m                             ⇒ processor forward m
    }

    override def preStart(): Unit = {
      context.actorSelection(s"akka.tcp://store@127.0.0.1:${port}/user/store") ! Identify(1)
    }
  }

  def port(system: ActorSystem) =
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress.port.get
}

class SharedLeveldbJournalSpec extends AkkaSpec(SharedLeveldbJournalSpec.config) with Cleanup {
  import SharedLeveldbJournalSpec._

  "A LevelDB store" can {
    "be shared by multiple actor systems" in {
      val storeSystem = ActorSystem("store", ConfigFactory.parseString(SharedLeveldbJournalSpec.config))
      val processorASystem = ActorSystem("processorA", ConfigFactory.parseString(SharedLeveldbJournalSpec.config))
      val processorBSystem = ActorSystem("processorB", ConfigFactory.parseString(SharedLeveldbJournalSpec.config))

      val processorAProbe = new TestProbe(processorASystem)
      val processorBProbe = new TestProbe(processorBSystem)

      storeSystem.actorOf(Props[SharedLeveldbStore], "store")

      val appA = processorASystem.actorOf(Props(classOf[ExampleApp], processorAProbe.ref, port(storeSystem)))
      val appB = processorBSystem.actorOf(Props(classOf[ExampleApp], processorBProbe.ref, port(storeSystem)))

      appA ! Persistent("a1")
      appB ! Persistent("b1")

      processorAProbe.expectMsg("a1")
      processorBProbe.expectMsg("b1")

      val recoveredAppA = processorASystem.actorOf(Props(classOf[ExampleApp], processorAProbe.ref, port(storeSystem)))
      val recoveredAppB = processorBSystem.actorOf(Props(classOf[ExampleApp], processorBProbe.ref, port(storeSystem)))

      recoveredAppA ! Persistent("a2")
      recoveredAppB ! Persistent("b2")

      processorAProbe.expectMsg("a1")
      processorAProbe.expectMsg("a2")

      processorBProbe.expectMsg("b1")
      processorBProbe.expectMsg("b2")
    }
  }
}
