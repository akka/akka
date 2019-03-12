/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.AbruptStageTerminationException
import akka.stream.scaladsl.{ Sink, Source }
import org.scalatest.WordSpecLike

import scala.concurrent.Future

object CustomGuardianAndMaterializerSpec {

  sealed trait GuardianProtocol
  case class Init(sender: ActorRef[String]) extends GuardianProtocol
  case class Msg(sender: ActorRef[String], msg: String) extends GuardianProtocol
  case object Complete extends GuardianProtocol
  case object Failed extends GuardianProtocol
}

class CustomGuardianAndMaterializerSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import CustomGuardianAndMaterializerSpec._

  val guardian = Behaviors.receive[GuardianProtocol] { (_, msg) =>
    Behaviors.same
  }

  implicit val mat = ActorMaterializer()

  "ActorMaterializer" should {

    "work with typed ActorSystem with custom guardian" in {
      val it: Future[String] = Source.single("hello").runWith(Sink.head)

      it.futureValue should ===("hello")
    }

    "should kill streams with bound actor context" in {
      var doneF: Future[Done] = null
      val behavior =
        Behaviors.setup[String] { ctx =>
          implicit val mat: ActorMaterializer = ActorMaterializer.boundToActor(ctx)
          doneF = Source.repeat("hello").runWith(Sink.ignore)

          Behaviors.receiveMessage[String](_ => Behaviors.stopped)
        }

      val actorRef = spawn(behavior)

      actorRef ! "kill"
      eventually(doneF.failed.futureValue shouldBe an[AbruptStageTerminationException])
    }
  }
}
