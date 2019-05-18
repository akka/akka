/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.coexistence
import akka.actor.Actor
import akka.actor.testkit.typed.TestException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed._
import akka.actor.typed.coexistence.UntypedSupervisingTypedSpec.{
  SpawnAnonFromUntyped,
  SpawnFromUntyped,
  TypedSpawnedFromUntypedConext,
  UntypedToTyped
}
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import akka.actor.typed.scaladsl.adapter._
import akka.{ actor => u }

import scala.concurrent.duration._

object ProbedBehavior {
  def behavior(probe: u.ActorRef): Behavior[String] = {
    Behaviors
      .receiveMessage[String] {
        case "throw" => throw TestException("oh dear")
      }
      .receiveSignal {
        case (_, s) =>
          probe ! s
          Behaviors.same
      }
  }
}

object UntypedSupervisingTypedSpec {

  case class SpawnFromUntyped(behav: Behavior[String], name: String)
  case class SpawnAnonFromUntyped(behav: Behavior[String])
  case class TypedSpawnedFromUntypedConext(actorRef: ActorRef[String])

  class UntypedToTyped extends Actor {

    override def receive: Receive = {
      case SpawnFromUntyped(behav, name) =>
        sender() ! TypedSpawnedFromUntypedConext(context.spawn(behav, name))
      case SpawnAnonFromUntyped(behav) =>
        sender() ! TypedSpawnedFromUntypedConext(context.spawnAnonymous(behav))
    }
  }
}

class UntypedSupervisingTypedSpec extends AkkaSpec with ImplicitSender {

  implicit val typedActorSystem: ActorSystem[Nothing] = system.toTyped
  val smallDuration = 50.millis

  "An untyped actor system that spawns typed actors" should {
    "default to stop for supervision" in {
      val probe = TestProbe()
      val underTest = system.spawn(ProbedBehavior.behavior(probe.ref), "a1")
      watch(underTest.toUntyped)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage(smallDuration)
      expectTerminated(underTest.toUntyped)
    }

    "default to stop for supervision for spawn anonymous" in {
      val probe = TestProbe()
      val underTest = system.spawnAnonymous(ProbedBehavior.behavior(probe.ref))
      watch(underTest.toUntyped)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage(smallDuration)
      expectTerminated(underTest.toUntyped)
    }

    "allows overriding the default" in {
      val probe = TestProbe()
      val value = Behaviors.supervise(ProbedBehavior.behavior(probe.ref)).onFailure(SupervisorStrategy.restart)
      val underTest = system.spawn(value, "a2")
      watch(underTest.toUntyped)
      underTest ! "throw"
      probe.expectMsg(PreRestart)
      probe.expectNoMessage(smallDuration)
      expectNoMessage(smallDuration)
    }

    "default to stop supervision (from context)" in {
      val untyped = system.actorOf(u.Props(new UntypedToTyped()))
      val probe = TestProbe()
      untyped ! SpawnFromUntyped(ProbedBehavior.behavior(probe.ref), "a3")
      val underTest = expectMsgType[TypedSpawnedFromUntypedConext].actorRef
      watch(underTest.toUntyped)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage(smallDuration)
      expectTerminated(underTest.toUntyped)
    }

    "allow overriding the default (from context)" in {
      val untyped = system.actorOf(u.Props(new UntypedToTyped()))
      val probe = TestProbe()
      val behavior = Behaviors.supervise(ProbedBehavior.behavior(probe.ref)).onFailure(SupervisorStrategy.restart)
      untyped ! SpawnFromUntyped(behavior, "a4")
      val underTest = expectMsgType[TypedSpawnedFromUntypedConext].actorRef
      watch(underTest.toUntyped)
      underTest ! "throw"
      probe.expectMsg(PreRestart)
      probe.expectNoMessage(smallDuration)
      expectNoMessage(smallDuration)
    }

    "default to stop supervision for spawn anonymous (from context)" in {
      val untyped = system.actorOf(u.Props(new UntypedToTyped()))
      val probe = TestProbe()
      untyped ! SpawnAnonFromUntyped(ProbedBehavior.behavior(probe.ref))
      val underTest = expectMsgType[TypedSpawnedFromUntypedConext].actorRef
      watch(underTest.toUntyped)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage(smallDuration)
      expectTerminated(underTest.toUntyped)
    }

  }
}
