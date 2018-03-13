/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ DeathPactException, SupervisorStrategy, TypedAkkaSpecWithShutdown }
import akka.testkit.typed.scaladsl.ActorTestKit
import com.typesafe.config.ConfigFactory

class FaultToleranceDocSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  override def config = ConfigFactory.parseString(
    """
      # silenced to not put noise in test logs
      akka.loglevel = OFF
    """)

  "Bubbling of failures" must {

    "have an example for the docs" in {

      // FIXME I think we could have much better examples of this but I'm stuck so this will have to do for now

      // #bubbling-example
      sealed trait Message
      case class Fail(text: String) extends Message

      val worker = Behaviors.receive[Message] { (ctx, msg) ⇒
        msg match {
          case Fail(text) ⇒ throw new RuntimeException(text)
        }
      }

      val middleManagementBehavior = Behaviors.setup[Message] { ctx ⇒
        ctx.log.info("Middle management starting up")
        val child = ctx.spawn(worker, "child")
        ctx.watch(child)

        // here we don't handle Terminated at all which means that
        // when the child fails or stops gracefully this actor will
        // fail with a DeathWatchException
        Behaviors.receive[Message] { (ctx, msg) ⇒
          child ! msg
          Behaviors.same
        }
      }

      val bossBehavior = Behaviors.supervise(Behaviors.setup[Message] { ctx ⇒
        ctx.log.info("Boss starting up")
        val middleManagment = ctx.spawn(middleManagementBehavior, "middle-management")
        ctx.watch(middleManagment)

        // here we don't handle Terminated at all which means that
        // when middle management fails with a DeathWatchException
        // this actor will also fail
        Behaviors.receive[Message] { (ctx, msg) ⇒
          middleManagment ! msg
          Behaviors.same
        }
      }).onFailure[DeathPactException](SupervisorStrategy.restart)

      // (spawn comes from the testkit)
      val boss = spawn(bossBehavior, "upper-management")
      boss ! Fail("ping")
      // #bubbling-example

    }
  }

}
