/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
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

  val echo = Actor.actorOf(new Actor {
    def receive = {
      case x ⇒
    }
  }).start()

  val testTimeout = if (Actor.defaultTimeout.duration < 400.millis) 500 millis else 100 millis

  override def afterAll { echo.stop() }

  "An Actor-based Future" must {

    "use the global default timeout if no implicit in scope" in {
      echo.timeout = 12
      within((Actor.TIMEOUT - 100).millis, (Actor.TIMEOUT + 300).millis) {
        val f = echo ? "hallo"
        intercept[FutureTimeoutException] { f.await }
      }
    }

    "use implicitly supplied timeout" in {
      implicit val timeout = Actor.Timeout(testTimeout)
      within(testTimeout - 100.millis, testTimeout + 300.millis) {
        val f = (echo ? "hallo").mapTo[String]
        intercept[FutureTimeoutException] { f.await }
        f.value must be(None)
      }
    }

    "use explicitly supplied timeout" in {
      within(testTimeout - 100.millis, testTimeout + 300.millis) {
        (echo.?("hallo")(timeout = testTimeout)).as[String] must be(None)
      }
    }

  }

}
