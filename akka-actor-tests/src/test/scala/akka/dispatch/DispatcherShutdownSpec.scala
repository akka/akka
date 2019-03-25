/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import akka.actor.ActorSystem
import akka.testkit.TestKit
import java.lang.management.ManagementFactory
import org.scalatest.{ Matchers, WordSpec }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class DispatcherShutdownSpec extends WordSpec with Matchers {

  "akka dispatcher" should {

    "eventually shutdown when used after system terminate" in {

      val threads = ManagementFactory.getThreadMXBean()
      def threadCount =
        threads
          .dumpAllThreads(false, false)
          .toList
          .map(_.getThreadName)
          .filter(_.startsWith("DispatcherShutdownSpec-akka.actor.default"))
          .size

      val system = ActorSystem("DispatcherShutdownSpec")
      threadCount should be > 0

      Await.ready(system.terminate(), 1.second)
      Await.ready(Future(akka.Done)(system.dispatcher), 1.second)

      TestKit.awaitCond(threadCount == 0, 3.second)
    }

  }

}
