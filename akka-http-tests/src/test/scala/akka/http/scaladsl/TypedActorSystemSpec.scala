/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.AkkaSpec

class TypedActorSystemSpec extends AkkaSpec {
  val typedSystem = ActorSystem(Behavior.empty[Any], "TypedSystem")

  "Http extension" should {
    "work with a typed actor system" in {
      Http(typedSystem.toUntyped)
    }
  }
}
