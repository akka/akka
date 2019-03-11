/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ DeathPactException, SupervisorStrategy }
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class FaultToleranceDocSpec extends ScalaTestWithActorTestKit("""
      # silenced to not put noise in test logs
      akka.loglevel = off
    """) with WordSpecLike {

  "Bubbling of failures" must {

    "have an example for the docs" in {

      // FIXME I think we could have much better examples of this but I'm stuck so this will have to do for now

      // #bubbling-example
      sealed trait Message
      case class Fail(text: String) extends Message

      val worker = Behaviors.receive[Message] { (context, message) =>
        message match {
          case Fail(text) => throw new RuntimeException(text)
        }
      }

      val middleManagementBehavior = Behaviors.setup[Message] { context =>
        context.log.info("Middle management starting up")
        val child = context.spawn(worker, "child")
        context.watch(child)

        // here we don't handle Terminated at all which means that
        // when the child fails or stops gracefully this actor will
        // fail with a DeathWatchException
        Behaviors.receive[Message] { (context, message) =>
          child ! message
          Behaviors.same
        }
      }

      val bossBehavior = Behaviors
        .supervise(Behaviors.setup[Message] { context =>
          context.log.info("Boss starting up")
          val middleManagement = context.spawn(middleManagementBehavior, "middle-management")
          context.watch(middleManagement)

          // here we don't handle Terminated at all which means that
          // when middle management fails with a DeathWatchException
          // this actor will also fail
          Behaviors.receiveMessage[Message] { message =>
            middleManagement ! message
            Behaviors.same
          }
        })
        .onFailure[DeathPactException](SupervisorStrategy.restart)

      // (spawn comes from the testkit)
      val boss = spawn(bossBehavior, "upper-management")
      boss ! Fail("ping")
      // #bubbling-example

    }
  }

}
