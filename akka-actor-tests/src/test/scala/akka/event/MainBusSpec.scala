/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.testkit.AkkaSpec
import akka.config.Configuration
import akka.util.duration._

object MainBusSpec {
  case class M(i: Int)
}

class MainBusSpec extends AkkaSpec(Configuration(
  "akka.actor.debug.lifecycle" -> true,
  "akka.actor.debug.mainbus" -> true)) {
  
  import MainBusSpec._

  "A MainBus" must {

    "allow subscriptions" in {
      val bus = new MainBus(true)
      bus.start(app)
      bus.subscribe(testActor, classOf[M])
      bus.publish(M(42))
      expectMsg(1 second, M(42))
    }

  }

}