/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowDispatcherSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))

  "Flow with dispatcher setting" must {
    "use the specified dispatcher" in {
      val probe = TestProbe()
      val p = Flow(List(1, 2, 3)).map(i ⇒
        { probe.ref ! Thread.currentThread().getName(); i }).
        consume(materializer)
      probe.receiveN(3) foreach {
        case s: String ⇒ s should startWith(system.name + "-akka.test.stream-dispatcher")
      }
    }
  }
}