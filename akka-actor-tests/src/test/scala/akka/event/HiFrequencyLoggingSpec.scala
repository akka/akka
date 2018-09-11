/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import akka.testkit._
import akka.testkit.TimingTest
import scala.concurrent.duration._

class HiFrequencyLoggingSpec extends AkkaSpec("akka.loglevel = INFO") {
  "HiFrequencyLogging" should {
    val log = Logging(system, getClass)

    "log as normal before reaching limit" in {
      val hiFrequency = new HiFrequencyLogging(system, maxCalls = 100, within = 10.seconds)
      EventFilter.info(pattern = "aaa.*", occurrences = 3) intercept {
        hiFrequency.filter(discardInfo ⇒ log.info("aaa-1{}", discardInfo))
        hiFrequency.filter(discardInfo ⇒ log.info("aaa-2{}", discardInfo))
        hiFrequency.filter(discardInfo ⇒ log.info("aaa-3{}", discardInfo))
      }
    }

    "discard log messages after reaching limit" in {
      val hiFrequency = new HiFrequencyLogging(system, maxCalls = 2, within = 10.seconds)
      EventFilter.info(pattern = "bbb.*", occurrences = 2) intercept {
        hiFrequency.filter(discardInfo ⇒ log.info("bbb-1{}", discardInfo)) should ===(true)
        hiFrequency.filter(discardInfo ⇒ log.info("bbb-2{}", discardInfo)) should ===(true)
        hiFrequency.filter(discardInfo ⇒ log.info("bbb-3{}", discardInfo)) should ===(false)
      }
    }

    "continue logging after reset" in {
      val hiFrequency = new HiFrequencyLogging(system, maxCalls = 2, within = 10.seconds)
      EventFilter.info(pattern = "ccc.*", occurrences = 2) intercept {
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-1{}", discardInfo)) should ===(true)
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-2{}", discardInfo)) should ===(true)
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-3{}", discardInfo)) should ===(false)
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-4{}", discardInfo)) should ===(false)
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-5{}", discardInfo)) should ===(false)
      }
      hiFrequency.reset()
      EventFilter.info(message = "ccc-6 ([3] similar log messages were discarded in last [10000 ms])", occurrences = 1) intercept {
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-6{}", discardInfo)) should ===(true)
      }
      EventFilter.info(message = "ccc-7", occurrences = 1) intercept {
        hiFrequency.filter(discardInfo ⇒ log.info("ccc-7{}", discardInfo)) should ===(true)
      }
    }

    "log once per second (example)" taggedAs TimingTest in {
      val hiFrequency = new HiFrequencyLogging(system, maxCalls = 1, within = 1.seconds)
      EventFilter.info(pattern = "ddd.*", occurrences = 3) intercept {
        (1 to 25).foreach { n ⇒
          hiFrequency.filter(discardInfo ⇒ log.info("ddd-{}{}", n, discardInfo))
          Thread.sleep(100)
        }
      }

    }

  }
}
