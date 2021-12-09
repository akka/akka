/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.EntityEnvelope.StartEntity
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object EntitySpec {

  val config = ConfigFactory.parseString(s"""
      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
    """)

  sealed trait TestProtocol
  final case class ReplyPlz(toMe: ActorRef[String]) extends TestProtocol
  final case class WhoAreYou(replyTo: ActorRef[String]) extends TestProtocol
  case object StopPlz extends TestProtocol
  case object PassivatePlz extends TestProtocol

  sealed trait IdTestProtocol
  final case class IdReplyPlz(id: String, toMe: ActorRef[String]) extends IdTestProtocol
  final case class IdWhoAreYou(id: String, replyTo: ActorRef[String]) extends IdTestProtocol
  case object IdStopPlz extends IdTestProtocol

  def behavior(context: EntityContext[TestProtocol], stopProbe: Option[ActorRef[String]] = None) = {

    val entityManager = context.manager
    Behaviors
      .receivePartial[TestProtocol] {
        case (ctx, PassivatePlz) =>
          entityManager ! Entity.Passivate(ctx.self)
          Behaviors.same

        case (_, StopPlz) =>
          stopProbe.foreach(_ ! "StopPlz")
          Behaviors.stopped

        case (_, WhoAreYou(replyTo)) =>
          replyTo ! s"I'm ${context.entityId}"
          Behaviors.same

        case (_, ReplyPlz(toMe)) =>
          toMe ! "Hello!"
          Behaviors.same
      }
      .receiveSignal {
        case (_, PostStop) =>
          stopProbe.foreach(_ ! "PostStop")
          Behaviors.same
      }
  }

  def behaviorWithId(context: EntityContext[IdTestProtocol]) = Behaviors.receive[IdTestProtocol] {
    case (_, IdStopPlz) =>
      Behaviors.stopped

    case (_, IdWhoAreYou(_, replyTo)) =>
      replyTo ! s"I'm ${context.entityId}"
      Behaviors.same

    case (_, IdReplyPlz(_, toMe)) =>
      toMe ! "Hello!"
      Behaviors.same
  }

}
class EntitySpec extends ScalaTestWithActorTestKit(EntitySpec.config) with AnyWordSpecLike with LogCapturing {

  import EntitySpec._

  val m = Map.empty[Int, Int]

  def entityWithEnvelope(
      entityKey: EntityTypeKey[TestProtocol],
      stopProbe: TestProbe[String]): Entity[TestProtocol, EntityEnvelope[TestProtocol]] =
    Entity(entityKey)(ctx => behavior(ctx, Some(stopProbe.ref))).withStopMessage(StopPlz)

  def entityWithEnvelope(entityKey: EntityTypeKey[TestProtocol]): Entity[TestProtocol, EntityEnvelope[TestProtocol]] =
    Entity(entityKey)(ctx => behavior(ctx, None)).withStopMessage(StopPlz)

  def entityWithoutEnvelope(entityKey: EntityTypeKey[IdTestProtocol]) =
    Entity(entityKey)(ctx => behaviorWithId(ctx))
      .withMessageExtractor(EntityMessageExtractor.noEnvelope[IdTestProtocol](IdStopPlz) {
        case IdReplyPlz(id, _)  => id
        case IdWhoAreYou(id, _) => id
        case other              => throw new IllegalArgumentException(s"Unexpected message $other")
      })
      .withStopMessage(IdStopPlz)

  "Local entity" must {

    "send message via entity manager using envelopes" in {
      val key = EntityTypeKey[TestProtocol]("envelope")
      val entityRef = system.initEntity(entityWithEnvelope(key))
      val p = TestProbe[String]()
      entityRef ! EntityEnvelope("test", ReplyPlz(p.ref))
      p.expectMessage("Hello!")
    }

    "send messages via entity manager without envelopes" in {
      val key = EntityTypeKey[IdTestProtocol]("no-envelope")
      val entityRef = system.initEntity(entityWithoutEnvelope(key))
      val p = TestProbe[String]()
      entityRef ! IdReplyPlz("test", p.ref)
      p.expectMessage("Hello!")
    }

    "be able to passivate with custom stop message" in {
      val stopProbe = TestProbe[String]()
      val key = EntityTypeKey[TestProtocol]("passivate-test")
      val entityRef = system.initEntity(entityWithEnvelope(key, stopProbe))

      val p = TestProbe[String]()

      entityRef ! EntityEnvelope(s"test1", ReplyPlz(p.ref))
      p.expectMessage("Hello!")

      entityRef ! EntityEnvelope(s"test1", PassivatePlz)
      stopProbe.expectMessage("StopPlz")
      stopProbe.expectMessage("PostStop")

      entityRef ! EntityEnvelope(s"test1", ReplyPlz(p.ref))
      p.expectMessage("Hello!")

    }

    "be able to passivate with PoisonPill" in {
      val stopProbe = TestProbe[String]()
      val p = TestProbe[String]()
      val key = EntityTypeKey[TestProtocol]("passivate-test-poison")

      val entityRef = system.initEntity(Entity(key)(ctx => behavior(ctx, Some(stopProbe.ref))))
      // no StopPlz stopMessage

      entityRef ! EntityEnvelope(s"test4", ReplyPlz(p.ref))
      p.expectMessage("Hello!")

      entityRef ! EntityEnvelope(s"test4", PassivatePlz)
      // no StopPlz
      stopProbe.expectMessage("PostStop")

      entityRef ! EntityEnvelope(s"test4", ReplyPlz(p.ref))
      p.expectMessage("Hello!")
    }

    "fail if init if typeName already in use, but with a different type" in {

      val key = EntityTypeKey[TestProtocol]("unique-name")
      system.initEntity(entityWithEnvelope(key))

      val ex = intercept[Exception] {
        val duplicatedKey = EntityTypeKey[IdTestProtocol]("unique-name")
        system.initEntity(entityWithoutEnvelope(duplicatedKey))
      }

      ex.getMessage should include("already initialized")
    }

    "EntityRef - tell" in {

      val key = EntityTypeKey[TestProtocol]("with-envelope-for-tell")
      system.initEntity(entityWithEnvelope(key).withStopMessage(StopPlz))

      val charlieRef = system.entityRefFor(key, "charlie")
      val p = TestProbe[String]()

      charlieRef ! WhoAreYou(p.ref)
      p.receiveMessage() should startWith("I'm charlie")

      charlieRef.tell(WhoAreYou(p.ref))
      p.receiveMessage() should startWith("I'm charlie")

      charlieRef ! StopPlz
    }

    "EntityRef - tell without envelope" in {

      val key = EntityTypeKey[IdTestProtocol]("without-envelope-for-tell")

      system.initEntity(entityWithoutEnvelope(key))

      val charlieRef = system.entityRefFor(key, "charlie")
      val p = TestProbe[String]()

      charlieRef ! IdWhoAreYou("charlie", p.ref)
      p.receiveMessage() should startWith("I'm charlie")

      charlieRef.tell(IdWhoAreYou("charlie", p.ref))
      p.receiveMessage() should startWith("I'm charlie")

      charlieRef ! IdStopPlz
    }

    "EntityRef - ask" in {
      val key = EntityTypeKey[TestProtocol]("entity-ref-ask")
      system.initEntity(entityWithEnvelope(key))

      val bobRef = system.entityRefFor(key, "bob")

      val replyBob = bobRef.ask(WhoAreYou(_)).futureValue.asInstanceOf[String]
      replyBob should startWith("I'm bob")

      val aliceRef = system.entityRefFor(key, "alice")
      val replyAlice = aliceRef.ask(WhoAreYou(_)).futureValue.asInstanceOf[String]
      replyAlice should startWith("I'm alice")

      bobRef ! StopPlz
      aliceRef ! StopPlz
    }

    "EntityRef - ActorContext.ask" in {
      val key = EntityTypeKey[TestProtocol]("actor-context-ask")
      system.initEntity(entityWithEnvelope(key))
      val peterRef = system.entityRefFor(key, "peter")

      val p = TestProbe[String]()

      spawn(Behaviors.setup[String] { ctx =>
        ctx.ask(peterRef, WhoAreYou.apply) {
          case Success(name) => name
          case Failure(ex)   => ex.getMessage
        }

        Behaviors.receiveMessage[String] { reply =>
          p.ref ! reply
          Behaviors.same
        }
      })

      val response = p.receiveMessage()
      response should startWith("I'm peter")

      peterRef ! StopPlz
    }

    "EntityRef - AskTimeoutException" in {
      val ignorantKey = EntityTypeKey[TestProtocol]("ignorant")

      system.initEntity(Entity(ignorantKey)(_ => Behaviors.ignore[TestProtocol]).withStopMessage(StopPlz))

      val ref = system.entityRefFor(ignorantKey, "sloppy")

      val reply = ref.ask(WhoAreYou(_))(Timeout(10.millis))
      val exc = reply.failed.futureValue

      exc.getClass should ===(classOf[AskTimeoutException])
      exc.getMessage should startWith("Ask timed out on")
      exc.getMessage should include(ignorantKey.toString)
      exc.getMessage should include("sloppy") // the entity id
      exc.getMessage should include(ref.toString)
      exc.getMessage should include(s"[${classOf[WhoAreYou].getName}]") // message class
      exc.getMessage should include("[10 ms]") // timeout
    }

    "handle typed StartEntity message" in {

      val probe = TestProbe[String]()
      val key = EntityTypeKey[TestProtocol]("start-entity")
      val entity = Entity(key) { ctx =>
        Behaviors.setup { _ =>
          probe ! s"${ctx.entityId} just started her day"
          behavior(ctx)
        }
      }
      val managerRef = system.initEntity(entity)

      managerRef ! StartEntity("Alice")
      probe.expectMessage("Alice just started her day")
    }

  }
}
