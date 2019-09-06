/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.Matchers
import org.scalatest.WordSpec

class PropsSpec extends WordSpec with Matchers with LogCapturing {

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
