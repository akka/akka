/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import scala.concurrent.ExecutionContext
import scala.concurrent.Promise

import org.scalatest.matchers.should.Matchers

import akka.Done
import akka.testkit.AkkaSpec

class SameThreadExecutionContextSpec extends AkkaSpec with Matchers {

  "The SameThreadExecutionContext" should {

    "should run follow up future operations in the same dispatcher" in {
      // covered by the respective impl test suites for sure but just in case
      val promise = Promise[Done]()
      val futureThreadNames = promise.future
        .map { _ =>
          Thread.currentThread().getName
        }(system.dispatcher)
        .map(firstName => firstName -> Thread.currentThread().getName)(ExecutionContext.parasitic)

      promise.success(Done)
      val (threadName1, threadName2) = futureThreadNames.futureValue
      threadName1 should ===(threadName2)
    }

    "should run follow up future operations in the same execution context" in {
      // covered by the respective impl test suites for sure but just in case
      val promise = Promise[Done]()
      val futureThreadNames = promise.future
        .map { _ =>
          Thread.currentThread().getName
        }(ExecutionContext.global)
        .map(firstName => firstName -> Thread.currentThread().getName)(ExecutionContext.parasitic)

      promise.success(Done)
      val (threadName1, threadName2) = futureThreadNames.futureValue
      threadName1 should ===(threadName2)
    }

  }

}
