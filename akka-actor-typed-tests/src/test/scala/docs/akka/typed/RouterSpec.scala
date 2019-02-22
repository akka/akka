/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package docs.akka.typed

// #pool
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Routers
import org.scalatest.WordSpecLike

// #pool

class RouterSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  // #pool
  object Worker {
    sealed trait Command
    case class DoLog(text: String) extends Command

    val behavior: Behavior[Command] = Behaviors.setup { ctx =>
      ctx.log.info("Starting worker")

      Behaviors.receiveMessage {
        case DoLog(text) =>
          ctx.log.info("Got message {}", text)
          Behaviors.same
      }
    }
  }

  // #pool

  "The routing sample" must {

    "show pool routing" in {
      spawn(Behaviors.setup { ctx =>
  // #pool
        val router = ctx.spawn(Routers.pool(poolSize = 4)(Worker.behavior), "worker-pool")

        (0 to 10).foreach { n =>
          router ! Worker.DoLog(s"msg $n")
        }
  // #pool
        Behaviors.empty
      })
    }
  }
}
