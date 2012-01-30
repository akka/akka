/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import akka.util.duration._
import akka.util.Duration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        getString("akka.version") must equal("2.0-SNAPSHOT")
        settings.ConfigVersion must equal("2.0-SNAPSHOT")

        getBoolean("akka.daemonic") must equal(false)
        getBoolean("akka.actor.serialize-messages") must equal(false)
        settings.SerializeAllMessages must equal(false)

        getInt("akka.scheduler.ticksPerWheel") must equal(512)
        settings.SchedulerTicksPerWheel must equal(512)

        getMilliseconds("akka.scheduler.tickDuration") must equal(100)
        settings.SchedulerTickDuration must equal(100 millis)
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        {
          c.getString("type") must equal("Dispatcher")
          c.getString("executor") must equal("thread-pool-executor")
          c.getInt("mailbox-capacity") must equal(-1)
          c.getMilliseconds("mailbox-push-timeout-time") must equal(10 * 1000)
          c.getString("mailboxType") must be("")
          c.getMilliseconds("shutdown-timeout") must equal(1 * 1000)
          c.getInt("throughput") must equal(5)
          c.getMilliseconds("throughput-deadline-time") must equal(0)
        }

        {
          val pool = c.getConfig("thread-pool-executor")
          import pool._
          getMilliseconds("keep-alive-time") must equal(60 * 1000)
          getDouble("core-pool-size-factor") must equal(3.0)
          getDouble("max-pool-size-factor") must equal(3.0)
          getInt("task-queue-size") must equal(-1)
          getString("task-queue-type") must equal("linked")
          getBoolean("allow-core-timeout") must equal(true)
        }
      }
    }
  }
}
