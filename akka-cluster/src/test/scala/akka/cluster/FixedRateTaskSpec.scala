/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.util.concurrent.atomic.AtomicInteger
import akka.testkit.AkkaSpec
import akka.util.duration._
import akka.testkit.TimingTest

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FixedRateTaskSpec extends AkkaSpec {

  "Task scheduled at fixed rate" must {
    "adjust for scheduler inaccuracy" taggedAs TimingTest in {
      val counter = new AtomicInteger
      FixedRateTask(system.scheduler, 150.millis, 150.millis) {
        counter.incrementAndGet()
      }
      5000.millis.sleep()
      counter.get must (be(33) or be(34))
    }

    "compensate for long running task" taggedAs TimingTest in {
      val counter = new AtomicInteger
      FixedRateTask(system.scheduler, 225.millis, 225.millis) {
        counter.incrementAndGet()
        80.millis.sleep()
      }
      5000.millis.sleep()
      counter.get must (be(22) or be(23))
    }
  }
}

