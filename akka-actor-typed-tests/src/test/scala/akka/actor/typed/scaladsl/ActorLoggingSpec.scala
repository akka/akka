/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.{ TypedAkkaSpec, scaladsl }
import akka.testkit.EventFilter
import akka.testkit.typed.TestKit
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter._

class ActorLoggingSpec extends TestKit(ConfigFactory.parseString(
  """akka.loggers = ["akka.testkit.TestEventListener"]""")) with TypedAkkaSpec {

  implicit val untyped = system.toUntyped

  "Logging in a typed actor" must {

    "be conveniently available from the ctx" in {
      val actor = EventFilter.info("Started", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        spawn(Behaviors.deferred[String] { ctx ⇒
          ctx.log.info("Started")

          Behaviors.immutable { (ctx, msg) ⇒
            ctx.log.info("got message {}", msg)
            Behaviors.same
          }
        }, "the-actor")
      }

      EventFilter.info("got message Hello", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        actor ! "Hello"
      }

    }

  }

  "Logging with MDC for a typed actor" must {

    "provide the MDC values in the log" in {
      val behaviors = LoggingBehaviors.withMdc[String](
        { (msg) ⇒
          if (msg == "first")
            Map(
              "message" -> msg,
              "first" -> true
            )
          else Map("message" -> msg)
        },
        Behaviors.deferred { ctx ⇒
          ctx.log.info("Starting")
          Behaviors.immutable { (ctx, msg) ⇒
            ctx.log.info("Got message!")
            Behaviors.same
          }
        }
      )

      val ref = spawn(behaviors)

      ref ! "first"
      ref ! "second"
      // FIXME how to test that logger actually sees MDC?
    }

  }

}
