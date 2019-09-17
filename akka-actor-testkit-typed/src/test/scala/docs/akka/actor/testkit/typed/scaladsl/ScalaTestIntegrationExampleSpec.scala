/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

import com.github.ghik.silencer.silent
import docs.akka.actor.testkit.typed.scaladsl.AsyncTestingExampleSpec.{ echoActor, Ping, Pong }

//#log-capturing
import akka.actor.testkit.typed.scaladsl.LogCapturing
//#scalatest-integration
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

//#scalatest-integration
//#log-capturing

@silent
//#scalatest-integration
class ScalaTestIntegrationExampleSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "Something" must {
    "behave correctly" in {
      val pinger = testKit.spawn(echoActor, "ping")
      val probe = testKit.createTestProbe[Pong]()
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
    }
  }
}
//#scalatest-integration

//#log-capturing
class LogCapturingExampleSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {

  "Something" must {
    "behave correctly" in {
      val pinger = testKit.spawn(echoActor, "ping")
      val probe = testKit.createTestProbe[Pong]()
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
    }
  }
}
//#log-capturing
