/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

// #pool
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
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

    def apply(): Behavior[Command] = Behaviors.setup { context =>
      context.log.info("Starting worker")

      Behaviors.receiveMessage {
        case DoLog(text) =>
          context.log.info("Got message {}", text)
          Behaviors.same
      }
    }
  }

  // #pool
  // #group
  val serviceKey = ServiceKey[Worker.Command]("log-worker")

  // #group
}

class RouterSpec extends ScalaTestWithActorTestKit("akka.loglevel=warning") with WordSpecLike with LogCapturing {
  import RouterSpec._

  "The routing sample" must {

    "show pool routing" in {
      // trixery to monitor worker but make the sample look like we use it directly
      val probe = createTestProbe[RouterSpec.Worker.Command]()
      object Worker {
        def apply(): Behavior[RouterSpec.Worker.Command] =
          Behaviors.monitor(probe.ref, RouterSpec.Worker())

        def DoLog(text: String) = RouterSpec.Worker.DoLog(text)
      }

      spawn(Behaviors.setup[Unit] { ctx =>
        // #pool
        val pool = Routers.pool(poolSize = 4)(
          // make sure the workers are restarted if they fail
          Behaviors.supervise(Worker()).onFailure[Exception](SupervisorStrategy.restart))
        val router = ctx.spawn(pool, "worker-pool")

        (0 to 10).foreach { n =>
          router ! Worker.DoLog(s"msg $n")
        }
        // #pool

        // #strategy
        val alternativePool = pool.withPoolSize(2).withRoundRobinRouting()
        // #strategy

        val alternativeRouter = ctx.spawn(alternativePool, "alternative-pool")
        alternativeRouter ! Worker.DoLog("msg")

        Behaviors.empty
      })

      probe.receiveMessages(10)
    }

    "show group routing" in {
      // trixery to monitor worker but make the sample look like we use it directly
      val probe = createTestProbe[RouterSpec.Worker.Command]()
      object Worker {
        def apply(): Behavior[RouterSpec.Worker.Command] =
          Behaviors.monitor(probe.ref, RouterSpec.Worker())

        def DoLog(text: String) = RouterSpec.Worker.DoLog(text)
      }

      spawn(Behaviors.setup[Unit] { ctx =>
        // #group
        // this would likely happen elsewhere - if we create it locally we
        // can just as well use a pool
        val worker = ctx.spawn(Worker(), "worker")
        ctx.system.receptionist ! Receptionist.Register(serviceKey, worker)

        val group = Routers.group(serviceKey)
        val router = ctx.spawn(group, "worker-group")

        // the group router will stash messages until it sees the first listing of registered
        // services from the receptionist, so it is safe to send messages right away
        (0 to 10).foreach { n =>
          router ! Worker.DoLog(s"msg $n")
        }
        // #group

        Behaviors.empty
      })

      probe.receiveMessages(10)
    }
  }
}
