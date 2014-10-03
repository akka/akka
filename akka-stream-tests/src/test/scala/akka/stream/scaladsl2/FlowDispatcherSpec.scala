/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.testkit.TestProbe
import akka.stream.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowDispatcherSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "Flow with dispatcher setting" must {
    "use the specified dispatcher" in {
      val probe = TestProbe()
      val p = Source(List(1, 2, 3)).map(i ⇒
        { probe.ref ! Thread.currentThread().getName(); i }).
        consume()
      probe.receiveN(3) foreach {
        case s: String ⇒ s should startWith(system.name + "-akka.test.stream-dispatcher")
      }
    }
  }
}
