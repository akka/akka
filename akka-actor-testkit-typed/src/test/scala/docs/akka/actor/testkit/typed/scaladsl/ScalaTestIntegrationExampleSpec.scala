/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

//#scalatest-integration
import akka.actor.testkit.typed.scaladsl.ActorTestKitScalaTestSpec
import org.scalatest.WordSpecLike

class ScalaTestIntegrationExampleSpec extends ActorTestKitScalaTestSpec with WordSpecLike {

  "Something" must {
    "behave correctly" in {
      val probe = createTestProbe[String]()
      // ... assertions etc.
    }
  }
}
//#scalatest-integration
