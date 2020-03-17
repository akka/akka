/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.coexistence
import akka.actor.Actor
import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.{ actor => classic }
import org.scalatest.wordspec.AnyWordSpecLike

object TypedSupervisingClassicSpec {

  sealed trait Protocol
  final case class SpawnClassicActor(props: classic.Props, replyTo: ActorRef[SpawnedClassicActor]) extends Protocol
  final case class SpawnedClassicActor(ref: classic.ActorRef)

  def classicActorOf() = Behaviors.receive[Protocol] {
    case (ctx, SpawnClassicActor(props, replyTo)) =>
      replyTo ! SpawnedClassicActor(ctx.actorOf(props))
      Behaviors.same
  }

  class CLassicActor(lifecycleProbe: ActorRef[String]) extends Actor {
    override def receive: Receive = {
      case "throw" => throw TestException("oh dear")
    }

    override def postStop(): Unit = {
      lifecycleProbe ! "postStop"
    }

    override def preStart(): Unit = {
      lifecycleProbe ! "preStart"
    }
  }

}

class TypedSupervisingClassicSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = INFO
  """.stripMargin) with AnyWordSpecLike with LogCapturing {
  import TypedSupervisingClassicSpec._

  "Typed supervising classic" should {
    "default to restart" in {
      val ref: ActorRef[Protocol] = spawn(classicActorOf())
      val lifecycleProbe = TestProbe[String]
      val probe = TestProbe[SpawnedClassicActor]
      ref ! SpawnClassicActor(classic.Props(new CLassicActor(lifecycleProbe.ref)), probe.ref)
      val spawnedClassic = probe.expectMessageType[SpawnedClassicActor].ref
      lifecycleProbe.expectMessage("preStart")
      spawnedClassic ! "throw"
      lifecycleProbe.expectMessage("postStop")
      // should be restarted because it is a classic actor
      lifecycleProbe.expectMessage("preStart")
    }
  }

}
