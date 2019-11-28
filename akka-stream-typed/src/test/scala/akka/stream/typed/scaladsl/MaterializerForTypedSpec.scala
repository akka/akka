/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.typed.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.AbruptStageTerminationException
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.scalatest.WordSpecLike

import scala.concurrent.Future
import scala.util.Success

class MaterializerForTypedSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "Materialization in typed" should {

    "use system materializer by default" in {
      val it: Future[String] = Source.single("hello").runWith(Sink.head)
      it.futureValue should ===("hello")
    }

    "allow for custom instances for special cases" in {
      val customMaterializer = Materializer(system)
      val it: Future[String] = Source.single("hello").runWith(Sink.head)(customMaterializer)

      it.futureValue should ===("hello")
    }

    "allow for actor context bound instances" in {
      val probe = testKit.createTestProbe[Any]()
      val actor = testKit.spawn(Behaviors.setup[String] { context =>
        val materializerForActor = Materializer(context)

        Behaviors.receiveMessage[String] {
          case "run" =>
            val f = Source.single("hello").runWith(Sink.head)(materializerForActor)
            f.onComplete(probe.ref ! _)(system.executionContext)
            Behaviors.same
        }
      })
      actor ! "run"
      probe.expectMessage(Success("hello"))

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
