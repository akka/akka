/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.testkit.AkkaSpec
import akka.actor.ActorSystem
import java.io.File
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit
import akka.util.Duration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.parseResource(classOf[ConfigSpec], "/akka-actor-reference.conf", ConfigParseOptions.defaults)) {

  "The default configuration file (i.e. akka-actor-reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val settings = system.settings
      val config = settings.config
      import config._

      getList("akka.boot").asScala.toSeq must equal(Nil)
      getString("akka.time-unit") must equal("seconds")
      settings.DefaultTimeUnit must equal(TimeUnit.SECONDS)
      getString("akka.version") must equal("2.0-SNAPSHOT")
      settings.ConfigVersion must equal("2.0-SNAPSHOT")

      getString("akka.actor.default-dispatcher.type") must equal("Dispatcher")
      getInt("akka.actor.default-dispatcher.keep-alive-time") must equal(60)
      getDouble("akka.actor.default-dispatcher.core-pool-size-factor") must equal(8.0)
      getDouble("akka.actor.default-dispatcher.max-pool-size-factor") must equal(8.0)
      getInt("akka.actor.default-dispatcher.task-queue-size") must equal(-1)
      getString("akka.actor.default-dispatcher.task-queue-type") must equal("linked")
      getBoolean("akka.actor.default-dispatcher.allow-core-timeout") must equal(true)
      getInt("akka.actor.default-dispatcher.mailbox-capacity") must equal(-1)
      getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time") must equal(10)
      getLong("akka.actor.dispatcher-shutdown-timeout") must equal(1)
      settings.DispatcherDefaultShutdown must equal(Duration(1, TimeUnit.SECONDS))
      getInt("akka.actor.default-dispatcher.throughput") must equal(5)
      settings.DispatcherThroughput must equal(5)
      getInt("akka.actor.default-dispatcher.throughput-deadline-time") must equal(-1)
      settings.DispatcherThroughputDeadlineTime must equal(Duration(-1, TimeUnit.SECONDS))
      getBoolean("akka.actor.serialize-messages") must equal(false)
      settings.SerializeAllMessages must equal(false)

      getString("akka.remote.layer") must equal("akka.cluster.netty.NettyRemoteSupport")
      getInt("akka.remote.server.port") must equal(2552)
    }
  }
}
