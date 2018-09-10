/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.ActorTestKitWordSpec
import docs.akka.typed.IntroSpec.HelloWorld

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
import akka.actor.Scheduler

//#imports2

object SpawnProtocolDocSpec {

  //#main
  object HelloWorldMain {
    val main: Behavior[SpawnProtocol] =
      Behaviors.setup { ctx ⇒
        // Start initial tasks
        // ctx.spawn(...)

        SpawnProtocol.behavior
      }
  }
  //#main
}

class SpawnProtocolDocSpec extends ActorTestKitWordSpec {

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
        system ? SpawnProtocol.Spawn(behavior = HelloWorld.greeter, name = "greeter", props = Props.empty)

      val greetedBehavior = Behaviors.receive[HelloWorld.Greeted] { (ctx, msg) ⇒
        ctx.log.info("Greeting for {} from {}", msg.whom, msg.from)
        Behaviors.stopped
      }

      val greetedReplyTo: Future[ActorRef[HelloWorld.Greeted]] =
        system ? SpawnProtocol.Spawn(greetedBehavior, name = "", props = Props.empty)

      for (greeterRef ← greeter; replyToRef ← greetedReplyTo) {
        greeterRef ! HelloWorld.Greet("Akka", replyToRef)
      }

      //#system-spawn

      Thread.sleep(500) // it will not fail if too short
      ActorTestKit.shutdown(system)
    }

  }

}
