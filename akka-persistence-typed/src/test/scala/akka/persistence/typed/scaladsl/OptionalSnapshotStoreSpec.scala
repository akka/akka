/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID

import akka.actor.typed.TypedAkkaSpecWithShutdown
import akka.actor.typed.scaladsl.adapter.{ TypedActorRefOps, TypedActorSystemOps }
import akka.event.Logging
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.Eventually

object OptionalSnapshotStoreSpec {

  sealed trait Command

  object AnyCommand extends Command

  final case class State(internal: String = UUID.randomUUID().toString)

  case class Event(id: Long = System.currentTimeMillis())

  def persistentBehavior(
    probe: TestProbe[State],
    name:  String           = UUID.randomUUID().toString) =
    PersistentBehaviors.receive[Command, Event, State](
      persistenceId = name,
      initialState = State(),
      commandHandler = CommandHandler.command {
        _ ⇒ Effect.persist(Event()).andThen(probe.ref ! _)
      },
      eventHandler = {
        case (_, _) ⇒ State()
      }
    ).snapshotWhen { case _ ⇒ true }

  def persistentBehaviorWithSnapshotPlugin(probe: TestProbe[State]) =
    persistentBehavior(probe).withSnapshotPluginId("akka.persistence.snapshot-store.local")

}

class OptionalSnapshotStoreSpec extends ActorTestKit with TypedAkkaSpecWithShutdown with Eventually {

  import OptionalSnapshotStoreSpec._

  override def config: Config = ConfigFactory.parseString(
    s"""
    akka.persistence.publish-plugin-commands = on
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.leveldb.dir = "target/journal-${classOf[OptionalSnapshotStoreSpec].getName}"

    akka.actor.warn-about-java-serializer-usage = off

    # snapshot store plugin is NOT defined, things should still work
    akka.persistence.snapshot-store.local.dir = "target/snapshots-${classOf[OptionalSnapshotStoreSpec].getName}/"
  """)

  private def logProbe[T](cl: Class[T]) = {
    val logProbe = TestProbe[Any]
    system.toUntyped.eventStream.subscribe(logProbe.ref.toUntyped, cl)
    logProbe
  }

  "Persistence extension" must {
    "initialize properly even in absence of configured snapshot store" in {
      val stateProbe = TestProbe[State]()
      val log = logProbe(classOf[Logging.Warning])
      spawn(persistentBehavior(stateProbe))
      val message = log.expectMessageType[Logging.Warning].message.toString
      message should include("No default snapshot store configured")
      stateProbe.expectNoMessage()
    }

    "fail if PersistentActor tries to saveSnapshot without snapshot-store available" in {
      val stateProbe = TestProbe[State]()
      val log = logProbe(classOf[Logging.Error])
      val persistentActor = spawn(persistentBehavior(stateProbe))
      persistentActor ! AnyCommand
      log.expectMessageType[Logging.Error].cause.getMessage should include("No snapshot store configured")
      stateProbe.expectMessageType[State]
    }

    "successfully save a snapshot when no default snapshot-store configured, yet PersistentActor picked one explicitly" in {
      val stateProbe = TestProbe[State]
      val persistentActor = spawn(persistentBehaviorWithSnapshotPlugin(stateProbe))
      persistentActor ! AnyCommand
      stateProbe.expectMessageType[State]
    }
  }
}
