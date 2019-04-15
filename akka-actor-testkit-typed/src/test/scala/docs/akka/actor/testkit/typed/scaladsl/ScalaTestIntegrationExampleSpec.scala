/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

import com.github.ghik.silencer.silent

//#scalatest-integration
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

//#scalatest-integration
@silent
//#scalatest-integration
class ScalaTestIntegrationExampleSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "Something" must {
    "behave correctly" in {
      val probe = createTestProbe[String]()
      // ... assertions etc.
    }
  }
}
//#scalatest-integration
