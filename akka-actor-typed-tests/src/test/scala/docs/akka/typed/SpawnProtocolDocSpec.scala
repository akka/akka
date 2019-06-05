/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import docs.akka.typed.IntroSpec.HelloWorld
import org.scalatest.WordSpecLike

//#imports1
import akka.actor.typed.Behavior
import akka.actor.typed.SpawnProtocol
import akka.actor.typed.scaladsl.Behaviors

//#imports1

//#imports2
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Props
import akka.util.Timeout
import akka.actor.typed.Scheduler

//#imports2

object SpawnProtocolDocSpec {

  //#main
  object HelloWorldMain {
    val main: Behavior[SpawnProtocol] =
      Behaviors.setup { context =>
        // Start initial tasks
        // context.spawn(...)

        SpawnProtocol.behavior
      }
  }
  //#main
}

class SpawnProtocolDocSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import SpawnProtocolDocSpec._

  "ActorSystem with SpawnProtocol" must {
    "be able to spawn actors" in {
      //#system-spawn

      val system: ActorSystem[SpawnProtocol] =
        ActorSystem(HelloWorldMain.main, "hello")

      // needed in implicit scope for ask (?)
      import akka.actor.typed.scaladsl.AskPattern._
      implicit val ec: ExecutionContext = system.executionContext
      implicit val timeout: Timeout = Timeout(3.seconds)
      implicit val scheduler: Scheduler = system.scheduler

      val greeter: Future[ActorRef[HelloWorld.Greet]] =
        system.ask(SpawnProtocol.Spawn(behavior = HelloWorld.greeter, name = "greeter", props = Props.empty))

      val greetedBehavior = Behaviors.receive[HelloWorld.Greeted] { (context, message) =>
        context.log.info("Greeting for {} from {}", message.whom, message.from)
        Behaviors.stopped
      }

      val greetedReplyTo: Future[ActorRef[HelloWorld.Greeted]] =
        system.ask(SpawnProtocol.Spawn(greetedBehavior, name = "", props = Props.empty))

      for (greeterRef <- greeter; replyToRef <- greetedReplyTo) {
        greeterRef ! HelloWorld.Greet("Akka", replyToRef)
      }

      //#system-spawn

      Thread.sleep(500) // it will not fail if too short
      ActorTestKit.shutdown(system)
    }

  }

}
