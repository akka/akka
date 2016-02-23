/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.config

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.Logging.DefaultLogger
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import akka.event.DefaultLoggingFilter

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        getString("akka.version") should ===(ActorSystem.Version)
        settings.ConfigVersion should ===(ActorSystem.Version)

        getBoolean("akka.daemonic") should ===(false)

        // WARNING: This setting should be off in the default reference.conf, but should be on when running
        // the test suite.
        getBoolean("akka.actor.serialize-messages") should ===(true)
        settings.SerializeAllMessages should ===(true)

        getInt("akka.scheduler.ticks-per-wheel") should ===(512)
        getDuration("akka.scheduler.tick-duration", TimeUnit.MILLISECONDS) should ===(10)
        getString("akka.scheduler.implementation") should ===("akka.actor.LightArrayRevolverScheduler")

        getBoolean("akka.daemonic") should ===(false)
        settings.Daemonicity should ===(false)

        getBoolean("akka.jvm-exit-on-fatal-error") should ===(true)
        settings.JvmExitOnFatalError should ===(true)

        getInt("akka.actor.deployment.default.virtual-nodes-factor") should ===(10)
        settings.DefaultVirtualNodesFactor should ===(10)

        getDuration("akka.actor.unstarted-push-timeout", TimeUnit.MILLISECONDS) should ===(10.seconds.toMillis)
        settings.UnstartedPushTimeout.duration should ===(10.seconds)

        settings.Loggers.size should ===(1)
        settings.Loggers.head should ===(classOf[DefaultLogger].getName)
        getStringList("akka.loggers").get(0) should ===(classOf[DefaultLogger].getName)

        getDuration("akka.logger-startup-timeout", TimeUnit.MILLISECONDS) should ===(5.seconds.toMillis)
        settings.LoggerStartTimeout.duration should ===(5.seconds)

        getString("akka.logging-filter") should ===(classOf[DefaultLoggingFilter].getName)

        getInt("akka.log-dead-letters") should ===(10)
        settings.LogDeadLetters should ===(10)

        getBoolean("akka.log-dead-letters-during-shutdown") should ===(true)
        settings.LogDeadLettersDuringShutdown should ===(true)
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        //General dispatcher config

        {
          c.getString("type") should ===("Dispatcher")
          c.getString("executor") should ===("default-executor")
          c.getDuration("shutdown-timeout", TimeUnit.MILLISECONDS) should ===(1 * 1000)
          c.getInt("throughput") should ===(5)
          c.getDuration("throughput-deadline-time", TimeUnit.MILLISECONDS) should ===(0)
          c.getBoolean("attempt-teamwork") should ===(true)
        }

        //Default executor config
        {
          val pool = c.getConfig("default-executor")
          pool.getString("fallback") should ===("fork-join-executor")
        }

        //Fork join executor config

        {
          val pool = c.getConfig("fork-join-executor")
          pool.getInt("parallelism-min") should ===(8)
          pool.getDouble("parallelism-factor") should ===(3.0)
          pool.getInt("parallelism-max") should ===(64)
          pool.getString("task-peeking-mode") should be("FIFO")
        }

        //Thread pool executor config

        {
          val pool = c.getConfig("thread-pool-executor")
          import pool._
          getDuration("keep-alive-time", TimeUnit.MILLISECONDS) should ===(60 * 1000)
          getDouble("core-pool-size-factor") should ===(3.0)
          getDouble("max-pool-size-factor") should ===(3.0)
          getInt("task-queue-size") should ===(-1)
          getString("task-queue-type") should ===("linked")
          getBoolean("allow-core-timeout") should ===(true)
          getString("fixed-pool-size") should ===("off")
        }

        // Debug config
        {
          val debug = config.getConfig("akka.actor.debug")
          import debug._
          getBoolean("receive") should ===(false)
          settings.AddLoggingReceive should ===(false)

          getBoolean("autoreceive") should ===(false)
          settings.DebugAutoReceive should ===(false)

          getBoolean("lifecycle") should ===(false)
          settings.DebugLifecycle should ===(false)

          getBoolean("fsm") should ===(false)
          settings.FsmDebugEvent should ===(false)

          getBoolean("event-stream") should ===(false)
          settings.DebugEventStream should ===(false)

          getBoolean("unhandled") should ===(false)
          settings.DebugUnhandledMessage should ===(false)

          getBoolean("router-misconfiguration") should ===(false)
          settings.DebugRouterMisconfiguration should ===(false)
        }

      }

      {
        val c = config.getConfig("akka.actor.default-mailbox")

        // general mailbox config

        {
          c.getInt("mailbox-capacity") should ===(1000)
          c.getDuration("mailbox-push-timeout-time", TimeUnit.MILLISECONDS) should ===(10 * 1000)
          c.getString("mailbox-type") should ===("akka.dispatch.UnboundedMailbox")
        }
      }
    }
  }
}
