/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed

class PropsSpec extends TypedSpecSetup {

  val dispatcherFirst = DispatcherDefault(DispatcherFromConfig("pool"))

  object `A Props` {

    def `must get first dispatcher`(): Unit = {
      dispatcherFirst.firstOrElse[DispatcherSelector](null) should ===(dispatcherFirst)
    }

    def `must yield all configs of some type`(): Unit = {
      dispatcherFirst.allOf[DispatcherSelector] should ===(DispatcherSelector.default() :: DispatcherSelector.fromConfig("pool") :: Nil)
    }
  }
}
