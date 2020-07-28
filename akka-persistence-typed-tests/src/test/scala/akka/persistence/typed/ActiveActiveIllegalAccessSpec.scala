/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.scaladsl.{ ActiveActiveEventSourcing, Effect, EventSourcedBehavior }
import akka.serialization.jackson.CborSerializable
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

object ActiveActiveIllegalAccessSpec {

  val R1 = ReplicaId("R1")
  val R2 = ReplicaId("R1")
  val AllReplicas = Set(R1, R2)

  sealed trait Command
  case class AccessInCommandHandler(replyTo: ActorRef[Thrown]) extends Command
  case class AccessInPersistCallback(replyTo: ActorRef[Thrown]) extends Command

  case class Thrown(exception: Option[Throwable])

  case class State(all: List[String]) extends CborSerializable

  def apply(entityId: String, replica: ReplicaId): Behavior[Command] = {
    ActiveActiveEventSourcing.withSharedJournal(
      entityId,
      replica,
      AllReplicas,
      PersistenceTestKitReadJournal.Identifier)(
      aaContext =>
        EventSourcedBehavior[Command, String, State](
          aaContext.persistenceId,
          State(Nil),
          (_, command) =>
            command match {
              case AccessInCommandHandler(replyTo) =>
                val exception = try {
                  aaContext.origin
                  None
                } catch {
                  case t: Throwable =>
                    Some(t)
                }
                replyTo ! Thrown(exception)
                Effect.none
              case AccessInPersistCallback(replyTo) =>
                Effect.persist("cat").thenRun { _ =>
                  val exception = try {
                    aaContext.concurrent
                    None
                  } catch {
                    case t: Throwable =>
                      Some(t)
                  }
                  replyTo ! Thrown(exception)
                }
            },
          (state, event) => state.copy(all = event :: state.all)))
  }

}

class ActiveActiveIllegalAccessSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config)
    with AnyWordSpecLike
    with LogCapturing
    with Eventually {
  import ActiveActiveIllegalAccessSpec._
  "ActiveActive" should {
    "detect illegal access to context in command handler" in {
      val probe = createTestProbe[Thrown]()
      val ref = spawn(ActiveActiveIllegalAccessSpec("id1", R1))
      ref ! AccessInCommandHandler(probe.ref)
      val thrown: Throwable = probe.expectMessageType[Thrown].exception.get
      thrown.getMessage should include("from the event handler")
    }
    "detect illegal access to context in persist thenRun" in {
      val probe = createTestProbe[Thrown]()
      val ref = spawn(ActiveActiveIllegalAccessSpec("id1", R1))
      ref ! AccessInPersistCallback(probe.ref)
      val thrown: Throwable = probe.expectMessageType[Thrown].exception.get
      thrown.getMessage should include("from the event handler")
    }
    "detect illegal access in the factory" in {
      val exception = intercept[UnsupportedOperationException] {
        ActiveActiveEventSourcing.withSharedJournal("id2", R1, AllReplicas, PersistenceTestKitReadJournal.Identifier) {
          aaContext =>
            aaContext.origin
            ???
        }
      }
      exception.getMessage should include("from the event handler")
    }
  }
}
