/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.SnapshotAdapter
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedSnapshotAdapterSpec {
  private val conf: Config = ConfigFactory.parseString(s"""
    akka.persistence.journal.leveldb.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/typed-persistence-${UUID.randomUUID().toString}"
  """)
  case class State(s: String) extends CborSerializable
  case class Command(c: String) extends CborSerializable
  case class Event(e: String) extends CborSerializable
  case class PersistedState(s: String) extends CborSerializable
}

class EventSourcedSnapshotAdapterSpec
    extends ScalaTestWithActorTestKit(EventSourcedSnapshotAdapterSpec.conf)
    with WordSpecLike
    with LogCapturing {
  import EventSourcedSnapshotAdapterSpec._
  import akka.actor.typed.scaladsl.adapter._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId.ofUniqueId(s"c${pidCounter.incrementAndGet()})")

  val queries: LeveldbReadJournal =
    PersistenceQuery(system.toClassic).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  private def behavior(pid: PersistenceId, probe: ActorRef[State]): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      pid,
      State(""),
      commandHandler = { (state, command) =>
        command match {
          case Command(c) if c == "shutdown" =>
            Effect.stop()
          case Command(c) if c == "get" =>
            probe.tell(state)
            Effect.none
          case _ =>
            Effect.persist(Event(command.c)).thenRun(newState => probe ! newState)
        }
      },
      eventHandler = { (state, evt) =>
        state.copy(s = state.s + "|" + evt.e)
      })

  "Snapshot adapter" must {

    "adapt snapshots to any" in {
      val pid = nextPid()
      val stateProbe = TestProbe[State]()
      val snapshotFromJournal = TestProbe[PersistedState]()
      val snapshotToJournal = TestProbe[State]()
      val b = behavior(pid, stateProbe.ref)
        .snapshotAdapter(new SnapshotAdapter[State]() {
          override def toJournal(state: State): Any = {
            snapshotToJournal.ref.tell(state)
            PersistedState(state.s)
          }
          override def fromJournal(from: Any): State = from match {
            case ps: PersistedState =>
              snapshotFromJournal.ref.tell(ps)
              State(ps.s)
          }
        })
        .snapshotWhen { (_, event, _) =>
          event.e.contains("snapshot")
        }

      val ref = spawn(b)

      ref.tell(Command("one"))
      stateProbe.expectMessage(State("|one"))
      ref.tell(Command("snapshot now"))
      stateProbe.expectMessage(State("|one|snapshot now"))
      snapshotToJournal.expectMessage(State("|one|snapshot now"))
      ref.tell(Command("shutdown"))

      val ref2 = spawn(b)
      snapshotFromJournal.expectMessage(PersistedState("|one|snapshot now"))
      ref2.tell(Command("get"))
      stateProbe.expectMessage(State("|one|snapshot now"))
    }

  }
}
