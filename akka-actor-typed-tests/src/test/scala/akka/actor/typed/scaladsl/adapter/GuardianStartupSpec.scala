/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl.adapter

import akka.actor.ActorSystemImpl
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.Matchers
import org.scalatest.WordSpec

class GuardianStartupSpec extends WordSpec with Matchers {

  "The user guardian" must {

    "should get a message sent to it early" in {
      var system: ActorSystem[String] = null
      @volatile var lastMsg = ""
      val guardianBehavior = Behaviors.receiveMessage[String] { msg =>
        lastMsg = msg
        Behaviors.same
      }
      try {
        system = ActorSystem(guardianBehavior, "GuardianStartupSpec-get-all")
        system ! "msg"

        val probe = TestProbe()(system)
        probe.awaitAssert(lastMsg should ===("msg"))

      } finally {
        if (system ne null)
          ActorTestKit.shutdown(system)
      }
    }

    "should not start before untyped system initialization is complete" in {
      var system: ActorSystem[String] = null
      @volatile var ok = false
      val guardianBehavior = Behaviors.setup[String] { ctx =>
        ctx.system.toUntyped.asInstanceOf[ActorSystemImpl].assertInitialized
        ok = true
        Behaviors.empty
      }
      try {
        system = ActorSystem(guardianBehavior, "GuardianStartupSpec-get-all")
        system ! "msg"

        val probe = TestProbe()(system)
        probe.awaitAssert(ok should ===(true))

      } finally {
        if (system ne null)
          ActorTestKit.shutdown(system)
      }
    }

  }

}
