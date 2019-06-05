/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import org.scalatest.Matchers
import org.scalatest.WordSpec

class PropsSpec extends WordSpec with Matchers {

  val dispatcherFirst = DispatcherDefault(DispatcherFromConfig("pool"))

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
