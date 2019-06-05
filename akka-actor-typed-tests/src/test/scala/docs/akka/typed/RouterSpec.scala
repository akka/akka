/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

// #pool
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Routers
import org.scalatest.WordSpecLike

// #pool

object RouterSpec {

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

  val serviceKey = ServiceKey[Worker.Command]("log-worker")
}

class RouterSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import RouterSpec._

  "The routing sample" must {

    "show pool routing" in {
      spawn(Behaviors.setup[Unit] { ctx =>
        // #pool
        val pool = Routers.pool(poolSize = 4)(() =>
          // make sure the workers are restarted if they fail
          Behaviors.supervise(Worker.behavior).onFailure[Exception](SupervisorStrategy.restart))
        val router = ctx.spawn(pool, "worker-pool")

        (0 to 10).foreach { n =>
          router ! Worker.DoLog(s"msg $n")
        }
        // #pool

        // #strategy
        val alternativePool = pool.withPoolSize(2).withRoundRobinRouting()
        // #strategy

        Behaviors.empty
      })
    }

    "show group routing" in {

      spawn(Behaviors.setup[Unit] { ctx =>
        // #group
        // this would likely happen elsewhere - if we create it locally we
        // can just as well use a pool
        val worker = ctx.spawn(Worker.behavior, "worker")
        ctx.system.receptionist ! Receptionist.Register(serviceKey, worker)

        val group = Routers.group(serviceKey);
        val router = ctx.spawn(group, "worker-group");

        // note that since registration of workers goes through the receptionist there is no
        // guarantee the router has seen any workers yet if we hit it directly like this and
        // these messages may end up in dead letters - in a real application you would not use
        // a group router like this - it is to keep the sample simple
        (0 to 10).foreach { n =>
          router ! Worker.DoLog(s"msg $n")
        }
        // #group

        Behaviors.empty
      })
    }
  }
}
