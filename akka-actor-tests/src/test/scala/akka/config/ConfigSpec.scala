/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import language.postfixOps
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.event.Logging.DefaultLogger
import java.util.concurrent.TimeUnit
import akka.event.DefaultLoggingFilter

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        getString("akka.version") should be("2.4-SNAPSHOT")
        settings.ConfigVersion should be("2.4-SNAPSHOT")

        getBoolean("akka.daemonic") should be(false)

        // WARNING: This setting should be off in the default reference.conf, but should be on when running
        // the test suite.
        getBoolean("akka.actor.serialize-messages") should be(true)
        settings.SerializeAllMessages should be(true)

        getInt("akka.scheduler.ticks-per-wheel") should be(512)
        getDuration("akka.scheduler.tick-duration", TimeUnit.MILLISECONDS) should be(10)
        getString("akka.scheduler.implementation") should be("akka.actor.LightArrayRevolverScheduler")

        getBoolean("akka.daemonic") should be(false)
        settings.Daemonicity should be(false)

        getBoolean("akka.jvm-exit-on-fatal-error") should be(true)
        settings.JvmExitOnFatalError should be(true)

        getInt("akka.actor.deployment.default.virtual-nodes-factor") should be(10)
        settings.DefaultVirtualNodesFactor should be(10)

        getDuration("akka.actor.unstarted-push-timeout", TimeUnit.MILLISECONDS) should be(10.seconds.toMillis)
        settings.UnstartedPushTimeout.duration should be(10.seconds)

        settings.Loggers.size should be(1)
        settings.Loggers.head should be(classOf[DefaultLogger].getName)
        getStringList("akka.loggers").get(0) should be(classOf[DefaultLogger].getName)

        getDuration("akka.logger-startup-timeout", TimeUnit.MILLISECONDS) should be(5.seconds.toMillis)
        settings.LoggerStartTimeout.duration should be(5.seconds)

        getString("akka.logging-filter") should be(classOf[DefaultLoggingFilter].getName)

        getInt("akka.log-dead-letters") should be(10)
        settings.LogDeadLetters should be(10)

        getBoolean("akka.log-dead-letters-during-shutdown") should be(true)
        settings.LogDeadLettersDuringShutdown should be(true)
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        //General dispatcher config

        {
          c.getString("type") should be("Dispatcher")
          c.getString("executor") should be("default-executor")
          c.getDuration("shutdown-timeout", TimeUnit.MILLISECONDS) should be(1 * 1000)
          c.getInt("throughput") should be(5)
          c.getDuration("throughput-deadline-time", TimeUnit.MILLISECONDS) should be(0)
          c.getBoolean("attempt-teamwork") should be(true)
        }

        //Default executor config
        {
          val pool = c.getConfig("default-executor")
          pool.getString("fallback") should be("fork-join-executor")
        }

        //Fork join executor config

        {
          val pool = c.getConfig("fork-join-executor")
          pool.getInt("parallelism-min") should be(8)
          pool.getDouble("parallelism-factor") should be(3.0)
          pool.getInt("parallelism-max") should be(64)
        }

        //Thread pool executor config

        {
          val pool = c.getConfig("thread-pool-executor")
          import pool._
          getDuration("keep-alive-time", TimeUnit.MILLISECONDS) should be(60 * 1000)
          getDouble("core-pool-size-factor") should be(3.0)
          getDouble("max-pool-size-factor") should be(3.0)
          getInt("task-queue-size") should be(-1)
          getString("task-queue-type") should be("linked")
          getBoolean("allow-core-timeout") should be(true)
        }

        // Debug config
        {
          val debug = config.getConfig("akka.actor.debug")
          import debug._
          getBoolean("receive") should be(false)
          settings.AddLoggingReceive should be(false)

          getBoolean("autoreceive") should be(false)
          settings.DebugAutoReceive should be(false)

          getBoolean("lifecycle") should be(false)
          settings.DebugLifecycle should be(false)

          getBoolean("fsm") should be(false)
          settings.FsmDebugEvent should be(false)

          getBoolean("event-stream") should be(false)
          settings.DebugEventStream should be(false)

          getBoolean("unhandled") should be(false)
          settings.DebugUnhandledMessage should be(false)

          getBoolean("router-misconfiguration") should be(false)
          settings.DebugRouterMisconfiguration should be(false)
        }

      }

      {
        val c = config.getConfig("akka.actor.default-mailbox")

        // general mailbox config

        {
          c.getInt("mailbox-capacity") should be(1000)
          c.getDuration("mailbox-push-timeout-time", TimeUnit.MILLISECONDS) should be(10 * 1000)
          c.getString("mailbox-type") should be("akka.dispatch.UnboundedMailbox")
        }
      }
    }
  }
}
