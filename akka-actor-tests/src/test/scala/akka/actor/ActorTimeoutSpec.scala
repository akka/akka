/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.BeforeAndAfterAll
import akka.util.duration._
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import java.util.concurrent.TimeoutException
import akka.dispatch.Await
import akka.util.Timeout
import akka.pattern.{ ask, AskTimeoutException }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorTimeoutSpec extends AkkaSpec with BeforeAndAfterAll with DefaultTimeout {

  def actorWithTimeout(t: Timeout): ActorRef = system.actorOf(Props(creator = () ⇒ new Actor {
    def receive = {
      case x ⇒
    }
  }, timeout = t))

  val defaultTimeout = system.settings.ActorTimeout.duration
  val testTimeout = if (system.settings.ActorTimeout.duration < 400.millis) 500 millis else 100 millis

  "An Actor-based Future" must {

    "use the global default timeout if no implicit in scope" in {
      within(defaultTimeout - 100.millis, defaultTimeout + 400.millis) {
        val echo = actorWithTimeout(Timeout(12))
        try {
          val d = system.settings.ActorTimeout.duration
          val f = echo ? "hallo"
          intercept[AskTimeoutException] { Await.result(f, d + d) }
        } finally { system.stop(echo) }
      }
    }

    "use implicitly supplied timeout" in {
      implicit val timeout = Timeout(testTimeout)
      within(testTimeout - 100.millis, testTimeout + 300.millis) {
        val echo = actorWithTimeout(Props.defaultTimeout)
        try {
          val f = (echo ? "hallo").mapTo[String]
          intercept[AskTimeoutException] { Await.result(f, testTimeout + testTimeout) }
        } finally { system.stop(echo) }
      }
    }

    "use explicitly supplied timeout" in {
      within(testTimeout - 100.millis, testTimeout + 300.millis) {
        val echo = actorWithTimeout(Props.defaultTimeout)
        val f = echo.?("hallo", testTimeout)
        try {
          intercept[AskTimeoutException] { Await.result(f, testTimeout + 300.millis) }
        } finally { system.stop(echo) }
      }
    }
  }
}
