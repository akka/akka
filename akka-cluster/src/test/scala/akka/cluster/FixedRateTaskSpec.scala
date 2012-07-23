/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import scala.concurrent.util.duration._
import akka.testkit.TimingTest
import akka.testkit.TestLatch
import scala.concurrent.Await

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FixedRateTaskSpec extends AkkaSpec {

  "Task scheduled at fixed rate" must {
    "adjust for scheduler inaccuracy" taggedAs TimingTest in {
      val startTime = System.nanoTime
      val n = 33
      val latch = new TestLatch(n)
      FixedRateTask(system.scheduler, 150.millis, 150.millis) {
        latch.countDown()
      }
      Await.ready(latch, 6.seconds)
      val rate = n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis
      rate must be(6.66 plusOrMinus (0.4))
    }

    "compensate for long running task" taggedAs TimingTest in {
      val startTime = System.nanoTime
      val n = 22
      val latch = new TestLatch(n)
      FixedRateTask(system.scheduler, 225.millis, 225.millis) {
        Thread.sleep(80)
        latch.countDown()
      }
      Await.ready(latch, 6.seconds)
      val rate = n * 1000.0 / (System.nanoTime - startTime).nanos.toMillis
      rate must be(4.4 plusOrMinus (0.3))
    }
  }
}

