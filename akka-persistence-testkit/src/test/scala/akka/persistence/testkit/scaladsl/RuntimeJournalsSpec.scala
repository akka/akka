/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.JournalProtocol.RecoverySuccess
import akka.persistence.JournalProtocol.ReplayMessages
import akka.persistence.JournalProtocol.ReplayedMessage
import akka.persistence.Persistence
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotProtocol.LoadSnapshot
import akka.persistence.SnapshotProtocol.LoadSnapshotResult
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria
import com.typesafe.config.ConfigFactory
import org.scalatest.Inside
import org.scalatest.wordspec.AnyWordSpecLike

object RuntimeJournalsSpec {

  private object Actor {
    sealed trait Command
    case class Save(text: String, replyTo: ActorRef[Done]) extends Command
    case class ShowMeWhatYouGot(replyTo: ActorRef[String]) extends Command
    case object Stop extends Command

    def apply(persistenceId: String, journal: String): Behavior[Command] =
      EventSourcedBehavior[Command, String, String](
        PersistenceId.ofUniqueId(persistenceId),
        "",
        (state, cmd) =>
          cmd match {
            case Save(text, replyTo) =>
              Effect.persist(text).thenRun(_ => replyTo ! Done)
            case ShowMeWhatYouGot(replyTo) =>
              replyTo ! state
              Effect.none
            case Stop =>
              Effect.stop()
          },
        (state, evt) => Seq(state, evt).filter(_.nonEmpty).mkString("|"))
        .withRetention(RetentionCriteria.snapshotEvery(1, Int.MaxValue))
        .withJournalPluginId(s"$journal.journal")
        .withJournalPluginConfig(Some(config(journal)))
        .withSnapshotPluginId(s"$journal.snapshot")
        .withSnapshotPluginConfig(Some(config(journal)))

  }

  private def config(journal: String) = {
    ConfigFactory.parseString(s"""
      $journal {
        journal.class = "${classOf[PersistenceTestKitPlugin].getName}"
        snapshot.class = "${classOf[PersistenceTestKitSnapshotPlugin].getName}"
      }
    """)
  }
}

class RuntimeJournalsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing with Inside {

  import RuntimeJournalsSpec._

  "The testkit journal and snapshot store plugins" must {

    "be possible to configure at runtime and use in multiple isolated instances" in {
      val probe = createTestProbe[Any]()

      {
        // one actor in each journal with same id
        val j1 = spawn(Actor("id1", "journal1"))
        val j2 = spawn(Actor("id1", "journal2"))
        j1 ! Actor.Save("j1m1", probe.ref)
        probe.receiveMessage()
        j2 ! Actor.Save("j2m1", probe.ref)
        probe.receiveMessage()
      }

      {
        def assertJournal(journal: String, expectedEvent: String) = {
          val ref = Persistence(system).journalFor(s"$journal.journal", config(journal))
          ref.tell(ReplayMessages(0, Long.MaxValue, Long.MaxValue, "id1", probe.ref.toClassic), probe.ref.toClassic)
          inside(probe.receiveMessage()) {
            case ReplayedMessage(persistentRepr) =>
              persistentRepr.persistenceId shouldBe "id1"
              persistentRepr.payload shouldBe expectedEvent
          }
          probe.expectMessage(RecoverySuccess(1))
        }

        assertJournal("journal1", "j1m1")
        assertJournal("journal2", "j2m1")
      }

      {
        def assertSnapshot(journal: String, expectedShapshot: String) = {
          val ref = Persistence(system).snapshotStoreFor(s"$journal.snapshot", config(journal))
          ref.tell(LoadSnapshot("id1", SnapshotSelectionCriteria.Latest, Long.MaxValue), probe.ref.toClassic)
          inside(probe.receiveMessage()) {
            case LoadSnapshotResult(Some(SelectedSnapshot(_, snapshot)), _) =>
              snapshot shouldBe expectedShapshot
          }
        }

        assertSnapshot("journal1", "j1m1")
        assertSnapshot("journal2", "j2m1")
      }
    }
  }
}
