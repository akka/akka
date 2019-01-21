/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.typed.PersistenceId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object SnapshotMutableStateSpec {

  class SlowInMemorySnapshotStore extends SnapshotStore {

    private var state = Map.empty[String, (Any, SnapshotMetadata)]

    def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
      Future.successful(state.get(persistenceId).map {
        case (snap, meta) ⇒ SelectedSnapshot(meta, snap)
      })
    }

    def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
      val value1 = snapshot.asInstanceOf[MutableState].value
      Thread.sleep(50)
      val value2 = snapshot.asInstanceOf[MutableState].value
      if (value1 != value2)
        Future.failed(new IllegalStateException(s"State changed from $value1 to $value2"))
      else {
        state = state.updated(metadata.persistenceId, (snapshot, metadata))
        Future.successful(())
      }
    }

    def deleteAsync(metadata: SnapshotMetadata) = ???
    def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria) = ???
  }

  def conf: Config = ConfigFactory.parseString(
    s"""
    akka.loglevel = INFO
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "slow-snapshot-store"

    slow-snapshot-store.class = "${classOf[SlowInMemorySnapshotStore].getName}"
    """)

  sealed trait Command
  final case object Increment extends Command
  final case class GetValue(replyTo: ActorRef[Int]) extends Command

  sealed trait Event
  case object Incremented extends Event

  final class MutableState(var value: Int)

  def counter(
    persistenceId: PersistenceId,
    snapshotProbe: ActorRef[String]): EventSourcedBehavior[Command, Event, MutableState] = {
    EventSourcedBehavior[Command, Event, MutableState](
      persistenceId,
      emptyState = new MutableState(0),
      commandHandler = (state, cmd) ⇒ cmd match {
        case Increment ⇒
          Effect.persist(Incremented)

        case GetValue(replyTo) ⇒
          replyTo ! state.value
          Effect.none
      },
      eventHandler = (state, evt) ⇒ evt match {
        case Incremented ⇒
          state.value += 1
          state
      }).onSnapshot {
        case (meta, Success(_)) ⇒ snapshotProbe ! s"snapshot-success-${meta.sequenceNr}"
        case (meta, Failure(_)) ⇒ snapshotProbe ! s"snapshot-failure-${meta.sequenceNr}"
      }
  }

}

class SnapshotMutableStateSpec extends ScalaTestWithActorTestKit(SnapshotMutableStateSpec.conf) with WordSpecLike {

  import SnapshotMutableStateSpec._

  private implicit val testSettings = TestKitSettings(system)

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  "A typed persistent actor with mutable state" must {

    "support mutable state by stashing commands while storing snapshot" in {
      val pid = nextPid()
      val snapshotProbe = TestProbe[String]()
      val snapshotState3: Behavior[Command] =
        counter(pid, snapshotProbe.ref).snapshotWhen { (state, _, _) ⇒ state.value == 3 }
      val c = spawn(snapshotState3)

      (1 to 5).foreach { _ ⇒
        c ! Increment
      }
      snapshotProbe.expectMessage(s"snapshot-success-3")

      val replyProbe = TestProbe[Int]()
      c ! GetValue(replyProbe.ref)
      replyProbe.expectMessage(5)
    }
  }
}
