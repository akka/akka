/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.{ WordSpec, BeforeAndAfterAll }
import org.scalatest.matchers.MustMatchers
import akka.testkit.TestKit
import akka.dispatch.FutureTimeoutException
import akka.util.duration._

class ActorTimeoutSpec
  extends WordSpec
  with BeforeAndAfterAll
  with MustMatchers
  with TestKit {

  def actorWithTimeout(t: Timeout): ActorRef = Actor.actorOf(Props(creator = () ⇒ new Actor {
    def receive = {
      case x ⇒
    }
  }, timeout = t))

  val testTimeout = if (Timeout.default.duration < 400.millis) 500 millis else 100 millis

  "An Actor-based Future" must {

    "use the global default timeout if no implicit in scope" in {
      within((Actor.TIMEOUT - 100).millis, (Actor.TIMEOUT + 400).millis) {
        val echo = actorWithTimeout(Timeout(12))
        try {
          val f = echo ? "hallo"
          intercept[FutureTimeoutException] { f.await }
        } finally { echo.stop }
      }
    }

    "use implicitly supplied timeout" in {
      implicit val timeout = Timeout(testTimeout)
      within(testTimeout - 100.millis, testTimeout + 300.millis) {
        val echo = actorWithTimeout(Props.defaultTimeout)
        try {
          val f = (echo ? "hallo").mapTo[String]
          intercept[FutureTimeoutException] { f.await }
          f.value must be(None)
        } finally { echo.stop }
      }
    }

    "use explicitly supplied timeout" in {
      within(testTimeout - 100.millis, testTimeout + 300.millis) {
        val echo = actorWithTimeout(Props.defaultTimeout)
        try { (echo.?("hallo", testTimeout)).as[String] must be(None) } finally { echo.stop }
      }
    }
  }
}
