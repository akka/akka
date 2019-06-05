/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl.adapter

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystemImpl
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.Matchers
import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures

class GuardianStartupSpec extends WordSpec with Matchers with ScalaFutures {

  "The user guardian" must {

    "get a message sent to it early" in {
      var system: ActorSystem[String] = null
      val sawMsg = new CountDownLatch(1)
      val guardianBehavior = Behaviors.receiveMessage[String] { msg =>
        if (msg == "msg") sawMsg.countDown()
        Behaviors.same
      }
      try {
        system = ActorSystem(guardianBehavior, "GuardianStartupSpec-get-all")
        system ! "msg"

        sawMsg.await(3, TimeUnit.SECONDS) should ===(true)

      } finally {
        if (system ne null)
          ActorTestKit.shutdown(system)
      }
    }

    "not start before untyped system initialization is complete" in {
      var system: ActorSystem[String] = null
      val initialized = new CountDownLatch(1)
      val guardianBehavior = Behaviors.setup[String] { ctx =>
        ctx.system.toUntyped.asInstanceOf[ActorSystemImpl].assertInitialized()
        initialized.countDown()
        Behaviors.empty
      }
      try {
        system = ActorSystem(guardianBehavior, "GuardianStartupSpec-initialized")
        system ! "msg"

        initialized.await(3, TimeUnit.SECONDS) should ===(true)

      } finally {
        if (system ne null)
          ActorTestKit.shutdown(system)
      }
    }

    "have its shutdown hook run on immediate shutdown (after start)" in {
      var system: ActorSystem[String] = null
      val stopHookExecuted = new CountDownLatch(1)
      // note that an immediately stopped (ActorSystem(Behaviors.stopped) is not allowed
      val guardianBehavior = Behaviors.setup[String](_ => Behaviors.stopped(() => stopHookExecuted.countDown()))

      try {
        system = ActorSystem(guardianBehavior, "GuardianStartupSpec-stop-hook")

        system.whenTerminated.futureValue
        stopHookExecuted.await(3, TimeUnit.SECONDS) should ===(true)

      } finally {
        if (system ne null)
          ActorTestKit.shutdown(system)
      }
    }

  }

}
