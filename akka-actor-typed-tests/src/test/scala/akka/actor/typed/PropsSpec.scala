/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PropsSpec extends AnyWordSpec with Matchers with LogCapturing {

  val dispatcherFirst = Props.empty.withDispatcherFromConfig("pool").withDispatcherDefault

  "A Props" must {

    "get first dispatcher" in {
      dispatcherFirst.firstOrElse[DispatcherSelector](null) should ===(dispatcherFirst)
    }

    "yield all configs of some type" in {
      dispatcherFirst.allOf[DispatcherSelector] should ===(
        DispatcherSelector.default() :: DispatcherSelector.fromConfig("pool") :: Nil)
    }
  }
}
