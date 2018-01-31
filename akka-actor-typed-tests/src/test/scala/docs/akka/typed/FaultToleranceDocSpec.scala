/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

import akka.actor.typed.TypedAkkaSpecWithShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class FaultToleranceDocSpec extends TestKit(ConfigFactory.parseString(
  """
    # silenced to not put noise in test logs
    akka.loglevel = OFF
  """)) with TypedAkkaSpecWithShutdown {

  "Bubbling of failures" must {

    "have an example for the docs" in {

      // #bubbling-example
      sealed trait Message
      case class Fail(text: String) extends Message

      val failingChildBehavior = Behaviors.immutable[Message] { (ctx, msg) ⇒
        // fail on every message
        msg match {
          case Fail(text) ⇒ throw new RuntimeException(text)
        }
      }

      val middleManagementBehavior = Behaviors.deferred[Message] { ctx ⇒
        val child = ctx.spawn(failingChildBehavior, "child")
        ctx.watch(child)

        Behaviors.immutable { (ctx, msg) ⇒
          child ! msg
          Behaviors.same
        }
      }

      val bossBehavior = Behaviors.deferred[Message] { ctx ⇒
        val middleManagment = ctx.spawn(middleManagementBehavior, "middle-management")
        ctx.watch(middleManagment)

        Behaviors.immutable { (ctx, msg) ⇒
          middleManagment ! msg
          Behaviors.same
        }
      }

      val boss = spawn(bossBehavior, "upper-management")
      boss ! Fail("ping")
      // #bubbling-example

      val probe = TestProbe[AnyRef]()
      probe.expectTerminated(boss, 1.second)

    }
  }

}
