/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import language.postfixOps
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import akka.actor.{ IOManager, ActorSystem }
import akka.event.Logging.DefaultLogger

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config

      {
        import config._

        getString("akka.version") must be("2.2.3")
        settings.ConfigVersion must be("2.2.3")

        getBoolean("akka.daemonic") must be(false)

        // WARNING: This setting must be off in the default reference.conf, but must be on when running
        // the test suite.
        getBoolean("akka.actor.serialize-messages") must be(true)
        settings.SerializeAllMessages must be(true)

        getInt("akka.scheduler.ticks-per-wheel") must be(512)
        getMilliseconds("akka.scheduler.tick-duration") must be(10)
        getString("akka.scheduler.implementation") must be("akka.actor.LightArrayRevolverScheduler")

        getBoolean("akka.daemonic") must be(false)
        settings.Daemonicity must be(false)

        getBoolean("akka.jvm-exit-on-fatal-error") must be(true)
        settings.JvmExitOnFatalError must be(true)

        getInt("akka.actor.deployment.default.virtual-nodes-factor") must be(10)
        settings.DefaultVirtualNodesFactor must be(10)

        getMilliseconds("akka.actor.unstarted-push-timeout") must be(10.seconds.toMillis)
        settings.UnstartedPushTimeout.duration must be(10.seconds)

        settings.Loggers.size must be(1)
        settings.Loggers.head must be(classOf[DefaultLogger].getName)
        getStringList("akka.loggers").get(0) must be(classOf[DefaultLogger].getName)

        getMilliseconds("akka.logger-startup-timeout") must be(5.seconds.toMillis)
        settings.LoggerStartTimeout.duration must be(5.seconds)

        getInt("akka.log-dead-letters") must be(10)
        settings.LogDeadLetters must be(10)

        getBoolean("akka.log-dead-letters-during-shutdown") must be(true)
        settings.LogDeadLettersDuringShutdown must be(true)
      }

      {
        val c = config.getConfig("akka.actor.default-dispatcher")

        //General dispatcher config

        {
          c.getString("type") must be("Dispatcher")
          c.getString("executor") must be("fork-join-executor")
          c.getMilliseconds("shutdown-timeout") must be(1 * 1000)
          c.getInt("throughput") must be(5)
          c.getMilliseconds("throughput-deadline-time") must be(0)
          c.getBoolean("attempt-teamwork") must be(true)
        }

        //Fork join executor config

        {
          val pool = c.getConfig("fork-join-executor")
          pool.getInt("parallelism-min") must be(8)
          pool.getDouble("parallelism-factor") must be(3.0)
          pool.getInt("parallelism-max") must be(64)
        }

        //Thread pool executor config

        {
          val pool = c.getConfig("thread-pool-executor")
          import pool._
          getMilliseconds("keep-alive-time") must be(60 * 1000)
          getDouble("core-pool-size-factor") must be(3.0)
          getDouble("max-pool-size-factor") must be(3.0)
          getInt("task-queue-size") must be(-1)
          getString("task-queue-type") must be("linked")
          getBoolean("allow-core-timeout") must be(true)
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

        // IO config
        {
          val io = config.getConfig("akka.io")
          val ioExtSettings = IOManager(system).settings
          ioExtSettings.readBufferSize must be(8192)
          io.getBytes("read-buffer-size") must be(ioExtSettings.readBufferSize)

          ioExtSettings.selectInterval must be(100)
          io.getInt("select-interval") must be(ioExtSettings.selectInterval)

          ioExtSettings.defaultBacklog must be(1000)
          io.getInt("default-backlog") must be(ioExtSettings.defaultBacklog)
        }
      }

      {
        val c = config.getConfig("akka.actor.default-mailbox")

        // general mailbox config

        {
          c.getInt("mailbox-capacity") must be(1000)
          c.getMilliseconds("mailbox-push-timeout-time") must be(10 * 1000)
          c.getString("mailbox-type") must be("akka.dispatch.UnboundedMailbox")
        }
      }
    }
  }
}
