/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import language.postfixOps

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import akka.actor.ActorSystem

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        getString("akka.version") must equal("2.1-SNAPSHOT")
        settings.ConfigVersion must equal("2.1-SNAPSHOT")

        getBoolean("akka.daemonic") must equal(false)
        getBoolean("akka.actor.serialize-messages") must equal(false)
        settings.SerializeAllMessages must equal(false)

        getInt("akka.scheduler.ticks-per-wheel") must equal(512)
        settings.SchedulerTicksPerWheel must equal(512)

        getMilliseconds("akka.scheduler.tick-duration") must equal(100)
        settings.SchedulerTickDuration must equal(100 millis)

        getBoolean("akka.daemonic") must be(false)
        settings.Daemonicity must be(false)

        getBoolean("akka.jvm-exit-on-fatal-error") must be(true)
        settings.JvmExitOnFatalError must be(true)
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        //General dispatcher config

        {
          c.getString("type") must equal("Dispatcher")
          c.getString("executor") must equal("fork-join-executor")
          c.getInt("mailbox-capacity") must equal(-1)
          c.getMilliseconds("mailbox-push-timeout-time") must equal(10 * 1000)
          c.getString("mailbox-type") must be("")
          c.getMilliseconds("shutdown-timeout") must equal(1 * 1000)
          c.getInt("throughput") must equal(5)
          c.getMilliseconds("throughput-deadline-time") must equal(0)
          c.getBoolean("attempt-teamwork") must equal(true)
        }

        //Fork join executor config

        {
          val pool = c.getConfig("fork-join-executor")
          pool.getInt("parallelism-min") must equal(8)
          pool.getDouble("parallelism-factor") must equal(3.0)
          pool.getInt("parallelism-max") must equal(64)
        }

        //Thread pool executor config

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

        // Debug config
        {
          val debug = config.getConfig("akka.actor.debug")
          import debug._
          getBoolean("receive") must be(false)
          settings.AddLoggingReceive must be(false)

          getBoolean("autoreceive") must be(false)
          settings.DebugAutoReceive must be(false)

          getBoolean("lifecycle") must be(false)
          settings.DebugLifecycle must be(false)

          getBoolean("fsm") must be(false)
          settings.FsmDebugEvent must be(false)

          getBoolean("event-stream") must be(false)
          settings.DebugEventStream must be(false)

          getBoolean("unhandled") must be(false)
          settings.DebugUnhandledMessage must be(false)

          getBoolean("router-misconfiguration") must be(false)
          settings.DebugRouterMisconfiguration must be(false)
        }
      }
    }
  }
}
