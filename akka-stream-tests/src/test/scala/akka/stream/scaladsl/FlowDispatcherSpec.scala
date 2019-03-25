/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.testkit.TestProbe
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings

class FlowDispatcherSpec extends StreamSpec(s"my-dispatcher = $${akka.test.stream-dispatcher}") {

  val defaultSettings = ActorMaterializerSettings(system)

  def testDispatcher(
      settings: ActorMaterializerSettings = defaultSettings,
      dispatcher: String = "akka.test.stream-dispatcher") = {

    implicit val materializer = ActorMaterializer(settings)

    val probe = TestProbe()
    val p = Source(List(1, 2, 3)).map(i => { probe.ref ! Thread.currentThread().getName(); i }).to(Sink.ignore).run()
    probe.receiveN(3).foreach {
      case s: String => s should startWith(system.name + "-" + dispatcher)
    }
  }

  "Flow with dispatcher setting" must {
    "use the default dispatcher" in testDispatcher()

    "use custom dispatcher" in testDispatcher(defaultSettings.withDispatcher("my-dispatcher"), "my-dispatcher")
  }
}
