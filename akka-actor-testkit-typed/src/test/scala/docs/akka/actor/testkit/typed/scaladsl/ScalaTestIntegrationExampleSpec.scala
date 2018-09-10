/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

//#scalatest-integration
import akka.actor.testkit.typed.scaladsl.ActorTestKitWordSpec

class ScalaTestIntegrationExampleSpec extends ActorTestKitWordSpec {

  "Something" must {
    "behave correctly" in {
      val probe = createTestProbe[String]()
      // ... assertions etc.
    }
  }
}
//#scalatest-integration
