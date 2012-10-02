/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import scala.concurrent.util.duration._
import akka.testkit._
import akka.testkit.TestEvent._
import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.{ ask, AskTimeoutException }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorTimeoutSpec extends AkkaSpec {

  val testTimeout = 200.millis.dilated
  val leeway = 500.millis.dilated

  system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message from.*hallo")))

  "An Actor-based Future" must {

    "use implicitly supplied timeout" in {
      implicit val timeout = Timeout(testTimeout)
      val echo = system.actorOf(Props.empty)
      val f = (echo ? "hallo")
      intercept[AskTimeoutException] { Await.result(f, testTimeout + leeway) }
    }

    "use explicitly supplied timeout" in {
      val echo = system.actorOf(Props.empty)
      val f = echo.?("hallo")(testTimeout)
      intercept[AskTimeoutException] { Await.result(f, testTimeout + leeway) }
    }
  }
}
