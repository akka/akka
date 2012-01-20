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
      import config._

      getString("akka.version") must equal("2.0-SNAPSHOT")
      settings.ConfigVersion must equal("2.0-SNAPSHOT")

      getBoolean("akka.daemonic") must equal(false)

      getString("akka.actor.default-dispatcher.type") must equal("Dispatcher")
      getMilliseconds("akka.actor.default-dispatcher.keep-alive-time") must equal(60 * 1000)
      getDouble("akka.actor.default-dispatcher.core-pool-size-factor") must equal(3.0)
      getDouble("akka.actor.default-dispatcher.max-pool-size-factor") must equal(3.0)
      getInt("akka.actor.default-dispatcher.task-queue-size") must equal(-1)
      getString("akka.actor.default-dispatcher.task-queue-type") must equal("linked")
      getBoolean("akka.actor.default-dispatcher.allow-core-timeout") must equal(true)
      getInt("akka.actor.default-dispatcher.mailbox-capacity") must equal(-1)
      getMilliseconds("akka.actor.default-dispatcher.mailbox-push-timeout-time") must equal(10 * 1000)
      getString("akka.actor.default-dispatcher.mailboxType") must be("")
      getMilliseconds("akka.actor.default-dispatcher.shutdown-timeout") must equal(1 * 1000)
      getInt("akka.actor.default-dispatcher.throughput") must equal(5)
      getMilliseconds("akka.actor.default-dispatcher.throughput-deadline-time") must equal(0)

      getBoolean("akka.actor.serialize-messages") must equal(false)
      settings.SerializeAllMessages must equal(false)

      getInt("akka.scheduler.ticksPerWheel") must equal(512)
      settings.SchedulerTicksPerWheel must equal(512)

      getMilliseconds("akka.scheduler.tickDuration") must equal(100)
      settings.SchedulerTickDuration must equal(100 millis)
    }
  }
}
