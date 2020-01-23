/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.internal

import akka.testkit.AkkaSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

class SameThreadExecutionContextSpec extends AkkaSpec with Matchers {

  "The SameThreadExecutionContext" should {

    "return a Scala specific version" in {
      val ec = SameThreadExecutionContext()
      if (util.Properties.versionNumberString.startsWith("2.12")) {
        ec.getClass.getName should ===("scala.concurrent.Future$InternalCallbackExecutor$")
      } else {
        // in 2.13 and higher parasitic is available
        ec.getClass.getName should ===("scala.concurrent.ExecutionContext$parasitic$")
      }
    }

    "should run follow up future operations in the same thread" in {
      // covered by the respective impl test suites for sure but just in case
      val (threadName1, threadName2) = Future {
        Thread.currentThread().getName
      }(system.dispatcher)
        .map(firstName => (firstName -> Thread.currentThread().getName))(SameThreadExecutionContext())
        .futureValue

      threadName1 should ===(threadName2)
    }

  }

}
