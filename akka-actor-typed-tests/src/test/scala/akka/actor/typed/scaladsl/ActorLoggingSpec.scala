/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.TypedAkkaSpec
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

}
