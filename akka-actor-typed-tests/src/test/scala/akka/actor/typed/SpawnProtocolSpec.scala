/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import scala.concurrent.duration._

import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout

object SpawnProtocolSpec {
  sealed trait Message
  final case class Ping(replyTo: ActorRef[Pong.type]) extends Message
  case object Pong

  val target: Behavior[Message] =
    Behaviors.receiveMessage {
      case Ping(replyTo) ⇒
        replyTo ! Pong
        Behaviors.same
    }
}

class SpawnProtocolSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  import SpawnProtocolSpec._
  implicit val testSettings = TestKitSettings(system)

  "Spawn behavior" must {
    "spawn child actor" in {
      val parentReply = TestProbe[ActorRef[Message]]()
      val parent = spawn(SpawnProtocol.behavior, "parent")
      parent ! SpawnProtocol.Spawn(target, "child", Props.empty, parentReply.ref)
      val child = parentReply.expectMessageType[ActorRef[Message]]
      child.path.name should ===("child")
      child.path.parent.name should ===("parent")

      val childReply = TestProbe[Pong.type]()
      child ! Ping(childReply.ref)
    }

    "have nice API for ask" in {
      val parent = spawn(SpawnProtocol.behavior, "parent2")
      import akka.actor.typed.scaladsl.AskPattern._
      implicit val timeout = Timeout(5.seconds)
      val parentReply = parent ? SpawnProtocol.Spawn(target, "child", Props.empty)
      val child = parentReply.futureValue
      val childReply = TestProbe[Pong.type]()
      child ! Ping(childReply.ref)
    }

    "be possible to use as guardian behavior" in {
      val sys = ActorSystem(SpawnProtocol.behavior, "SpawnProtocolSpec2")
      try {
        val guardianReply = TestProbe[ActorRef[Message]]()(sys)
        sys ! SpawnProtocol.Spawn(target, "child1", Props.empty, guardianReply.ref)
        val child1 = guardianReply.expectMessageType[ActorRef[Message]]
        child1.path.elements.mkString("/", "/", "") should ===("/user/child1")

        sys ! SpawnProtocol.Spawn(target, "child2", Props.empty, guardianReply.ref)
        val child2 = guardianReply.expectMessageType[ActorRef[Message]]
        child2.path.elements.mkString("/", "/", "") should ===("/user/child2")
      } finally {
        ActorTestKit.shutdown(sys)
      }
    }
  }
}

class StubbedSpawnProtocolSpec extends TypedAkkaSpec {

  import SpawnProtocolSpec._

  "Stubbed Spawn behavior" must {

    "spawn with given name" in {
      val parentReply = TestInbox[ActorRef[Message]]()
      val testkit = BehaviorTestKit(SpawnProtocol.behavior)
      testkit.run(SpawnProtocol.Spawn(target, "child", Props.empty, parentReply.ref))
      val child = parentReply.receiveMessage()
      child.path.name should ===("child")
      testkit.expectEffect(Effects.spawned(target, "child"))
    }

    "spawn anonymous when name undefined" in {
      val parentReply = TestInbox[ActorRef[Message]]()
      val testkit = BehaviorTestKit(SpawnProtocol.behavior)
      testkit.run(SpawnProtocol.Spawn(target, "", Props.empty, parentReply.ref))
      val child = parentReply.receiveMessage()
      child.path.name should startWith("$")
      testkit.expectEffect(Effects.spawnedAnonymous(target))
    }
  }

}

