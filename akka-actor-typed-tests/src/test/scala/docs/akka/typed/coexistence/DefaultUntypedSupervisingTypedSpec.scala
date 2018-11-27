/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed.coexistence

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.TestKit
import docs.akka.typed.coexistence.TypedWatchingUntypedSpec.Typed
import akka.{ actor ⇒ untyped }
import docs.akka.typed.coexistence.TypedWatchingUntypedSpec.Typed._
import org.scalatest.WordSpec

class DefaultUntypedSupervisingTypedSpec extends WordSpec {

  "Untyped creating typed" must {
    "allow spawning" in {
      //#spawn-untyped
      val system = untyped.ActorSystem("DefaultUntypedSupervisingTYped")
      // Parent is the untyped user guardian which defaults to restartint failed children
      val typed: ActorRef[Typed.Command] = system.spawn(Typed.behavior, "TypedActorWithUserAsParent")
      //#spawn-untyped
      typed ! Pong
      TestKit.shutdownActorSystem(system)
    }
  }

}
