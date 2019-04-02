/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.coexistence
import akka.actor.Actor
import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.{ WordSpec, WordSpecLike }
import akka.actor.typed.scaladsl.adapter._
import akka.{ actor => untyped }

import scala.concurrent.duration._

object TypedSupervisingUntypedSpec {

  sealed trait Protocol
  final case class SpawnUntypedActor(props: untyped.Props, replyTo: ActorRef[SpawnedUntypedActor]) extends Protocol
  final case class SpawnedUntypedActor(ref: untyped.ActorRef)

  def untypedActorOf() = Behaviors.receive[Protocol] {
    case (ctx, SpawnUntypedActor(props, replyTo)) =>
      replyTo ! SpawnedUntypedActor(ctx.actorOf(props))
      Behaviors.same
  }

  class UntypedActor(lifecycleProbe: ActorRef[String]) extends Actor {
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

class TypedSupervisingUntypedSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = INFO
  """.stripMargin) with WordSpecLike {
  import TypedSupervisingUntypedSpec._

  "Typed supervising untyped" should {
    "default to restart" in {
      val ref: ActorRef[Protocol] = spawn(untypedActorOf())
      val lifecycleProbe = TestProbe[String]
      val probe = TestProbe[SpawnedUntypedActor]
      ref ! SpawnUntypedActor(untyped.Props(new UntypedActor(lifecycleProbe.ref)), probe.ref)
      val spawnedUntyped = probe.expectMessageType[SpawnedUntypedActor].ref
      lifecycleProbe.expectMessage("preStart")
      spawnedUntyped ! "throw"
      lifecycleProbe.expectMessage("postStop")
      // should be restarted because it is an untyped actor
      lifecycleProbe.expectMessage("preStart")
    }
  }

}
