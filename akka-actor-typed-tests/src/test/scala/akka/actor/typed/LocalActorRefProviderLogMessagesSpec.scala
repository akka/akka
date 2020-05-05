/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.{ LoggingTestKit, ScalaTestWithActorTestKit }
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import org.scalatest.wordspec.AnyWordSpecLike

object LocalActorRefProviderLogMessagesSpec {
  val config = """
    akka {
      loglevel = DEBUG # test verifies debug
      log-dead-letters = on
      actor {
        debug.unhandled = on
      }
    }
  """
}

class LocalActorRefProviderLogMessagesSpec
    extends ScalaTestWithActorTestKit(LocalActorRefProviderLogMessagesSpec.config)
    with AnyWordSpecLike {

  "An LocalActorRefProvider" must {

    "logs on dedicated 'serialization' logger when an ActorRef can't be deserialized" in {
      val provider = system.asInstanceOf[ActorSystemAdapter[_]].provider
      val invalidPath = provider.rootPath / "user" / "invalid"

      LoggingTestKit
        .debug("Resolve (deserialization)")
        .withLoggerName("akka.actor.LocalActorRefProvider.Deserialization")
        .expect {
          provider.resolveActorRef(invalidPath)
        }

    }
  }
}
