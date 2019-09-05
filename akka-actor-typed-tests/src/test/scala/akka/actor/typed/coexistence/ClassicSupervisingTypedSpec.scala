/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.coexistence
import akka.actor.Actor
import akka.actor.testkit.typed.TestException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed._
import akka.actor.typed.coexistence.ClassicSupervisingTypedSpec.{
  ClassicToTyped,
  SpawnAnonFromClassic,
  SpawnFromClassic,
  TypedSpawnedFromClassicContext
}
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import akka.actor.typed.scaladsl.adapter._
import akka.{ actor => u }

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

object ClassicSupervisingTypedSpec {

  case class SpawnFromClassic(behav: Behavior[String], name: String)
  case class SpawnAnonFromClassic(behav: Behavior[String])
  case class TypedSpawnedFromClassicContext(actorRef: ActorRef[String])

  class ClassicToTyped extends Actor {

    override def receive: Receive = {
      case SpawnFromClassic(behav, name) =>
        sender() ! TypedSpawnedFromClassicContext(context.spawn(behav, name))
      case SpawnAnonFromClassic(behav) =>
        sender() ! TypedSpawnedFromClassicContext(context.spawnAnonymous(behav))
    }
  }
}

class ClassicSupervisingTypedSpec
    extends AkkaSpec("akka.actor.testkit.typed.expect-no-message-default = 50 ms")
    with ImplicitSender {

  implicit val typedActorSystem: ActorSystem[Nothing] = system.toTyped

  "A classic actor system that spawns typed actors" should {
    "default to stop for supervision" in {
      val probe = TestProbe()
      val underTest = system.spawn(ProbedBehavior.behavior(probe.ref), "a1")
      watch(underTest.toClassic)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage()
      expectTerminated(underTest.toClassic)
    }

    "default to stop for supervision for spawn anonymous" in {
      val probe = TestProbe()
      val underTest = system.spawnAnonymous(ProbedBehavior.behavior(probe.ref))
      watch(underTest.toClassic)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage()
      expectTerminated(underTest.toClassic)
    }

    "allows overriding the default" in {
      val probe = TestProbe()
      val value = Behaviors.supervise(ProbedBehavior.behavior(probe.ref)).onFailure(SupervisorStrategy.restart)
      val underTest = system.spawn(value, "a2")
      watch(underTest.toClassic)
      underTest ! "throw"
      probe.expectMsg(PreRestart)
      probe.expectNoMessage()
      expectNoMessage()
    }

    "default to stop supervision (from context)" in {
      val classic = system.actorOf(u.Props(new ClassicToTyped()))
      val probe = TestProbe()
      classic ! SpawnFromClassic(ProbedBehavior.behavior(probe.ref), "a3")
      val underTest = expectMsgType[TypedSpawnedFromClassicContext].actorRef
      watch(underTest.toClassic)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage()
      expectTerminated(underTest.toClassic)
    }

    "allow overriding the default (from context)" in {
      val classic = system.actorOf(u.Props(new ClassicToTyped()))
      val probe = TestProbe()
      val behavior = Behaviors.supervise(ProbedBehavior.behavior(probe.ref)).onFailure(SupervisorStrategy.restart)
      classic ! SpawnFromClassic(behavior, "a4")
      val underTest = expectMsgType[TypedSpawnedFromClassicContext].actorRef
      watch(underTest.toClassic)
      underTest ! "throw"
      probe.expectMsg(PreRestart)
      probe.expectNoMessage()
      expectNoMessage()
    }

    "default to stop supervision for spawn anonymous (from context)" in {
      val classic = system.actorOf(u.Props(new ClassicToTyped()))
      val probe = TestProbe()
      classic ! SpawnAnonFromClassic(ProbedBehavior.behavior(probe.ref))
      val underTest = expectMsgType[TypedSpawnedFromClassicContext].actorRef
      watch(underTest.toClassic)
      underTest ! "throw"
      probe.expectMsg(PostStop)
      probe.expectNoMessage()
      expectTerminated(underTest.toClassic)
    }

  }
}
