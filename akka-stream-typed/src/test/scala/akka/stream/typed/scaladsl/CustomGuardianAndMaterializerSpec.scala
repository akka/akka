/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.stream.typed.scaladsl

import scala.concurrent.Future

import akka.actor.typed.ActorRef
import akka.actor.typed.TypedAkkaSpecWithShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.typed.ActorMaterializer
import akka.testkit.typed.TestKit

object CustomGuardianAndMaterializerSpec {

  sealed trait GuardianProtocol
  case class Init(sender: ActorRef[String]) extends GuardianProtocol
  case class Msg(sender: ActorRef[String], msg: String) extends GuardianProtocol
  case object Complete extends GuardianProtocol
  case object Failed extends GuardianProtocol
}

class CustomGuardianAndMaterializerSpec extends TestKit with TypedAkkaSpecWithShutdown {
  import CustomGuardianAndMaterializerSpec._

  val guardian = Behaviors.immutable[GuardianProtocol] {
    (_, msg) ⇒ Behaviors.same
  }

  implicit val mat = ActorMaterializer()

  "ActorMaterializer" should {

    "work with typed ActorSystem with custom guardian" in {
      val it: Future[String] = Source.single("hello").runWith(Sink.head)

      it.futureValue should ===("hello")
    }

  }

}
