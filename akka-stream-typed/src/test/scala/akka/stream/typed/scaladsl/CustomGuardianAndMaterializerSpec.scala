/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.{ AbruptStageTerminationException, Materializer }
import akka.stream.scaladsl.{ Sink, Source }
import org.scalatest.WordSpecLike

import scala.concurrent.Future

class CustomGuardianAndMaterializerSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "Materializer" should {

    "be provided out of the box from typed ActorSystem" in {
      val it: Future[String] = Source.single("hello").runWith(Sink.head)

      it.futureValue should ===("hello")
    }

    "be possible to create from typed ActorSystem" in {
      implicit val mat = Materializer(system)
      val it: Future[String] = Source.single("hello").runWith(Sink.head)

      it.futureValue should ===("hello")
    }

    "should kill streams with bound actor context" in {
      var doneF: Future[Done] = null
      val behavior =
        Behaviors.setup[String] { ctx =>
          implicit val mat: Materializer = Materializer(ctx)
          doneF = Source.repeat("hello").runWith(Sink.ignore)

          Behaviors.receiveMessage[String](_ => Behaviors.stopped)
        }

      val actorRef = spawn(behavior)

      actorRef ! "kill"
      eventually(doneF.failed.futureValue shouldBe an[AbruptStageTerminationException])
    }
  }
}
