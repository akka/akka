/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.testkit.TestProbe
import akka.stream.testkit.AkkaSpec
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings

class FlowDispatcherSpec extends AkkaSpec("my-dispatcher = ${akka.test.stream-dispatcher}") {

  val defaultSettings = MaterializerSettings(system)

  def testDispatcher(settings: MaterializerSettings = defaultSettings, dispatcher: String = "akka.test.stream-dispatcher") = {

    implicit val materializer = FlowMaterializer(settings)

    val probe = TestProbe()
    val p = Source(List(1, 2, 3)).map(i ⇒
      { probe.ref ! Thread.currentThread().getName(); i }).
      to(Sink.ignore).run()
    probe.receiveN(3) foreach {
      case s: String ⇒ s should startWith(system.name + "-" + dispatcher)
    }
  }

  "Flow with dispatcher setting" must {
    "use the default dispatcher" in testDispatcher()

    "use custom dispatcher" in testDispatcher(defaultSettings.withDispatcher("my-dispatcher"), "my-dispatcher")
  }
}
