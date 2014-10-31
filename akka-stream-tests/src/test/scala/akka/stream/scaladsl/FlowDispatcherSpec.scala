/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.testkit.TestProbe
import akka.stream.testkit.AkkaSpec
import akka.stream.FlowMaterializer

class FlowDispatcherSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "Flow with dispatcher setting" must {
    "use the specified dispatcher" in {
      val probe = TestProbe()
      val p = Source(List(1, 2, 3)).map(i ⇒
        { probe.ref ! Thread.currentThread().getName(); i }).
        to(Sink.ignore).run()
      probe.receiveN(3) foreach {
        case s: String ⇒ s should startWith(system.name + "-akka.test.stream-dispatcher")
      }
    }
  }
}
