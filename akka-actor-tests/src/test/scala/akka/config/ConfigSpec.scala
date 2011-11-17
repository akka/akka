/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.testkit.AkkaSpec
import akka.actor.ActorSystem

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ActorSystem("ConfigSpec", Configuration.fromFile("config/akka-reference.conf"))) {

  "The default configuration file (i.e. akka-reference.conf)" must {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {

      val config = system.settings.config
      import config._

      getList("akka.boot") must equal(Nil)
      getString("akka.time-unit") must equal(Some("seconds"))
      getString("akka.version") must equal(Some("2.0-SNAPSHOT"))

      getString("akka.actor.default-dispatcher.type") must equal(Some("Dispatcher"))
      getInt("akka.actor.default-dispatcher.keep-alive-time") must equal(Some(60))
      getDouble("akka.actor.default-dispatcher.core-pool-size-factor") must equal(Some(8.0))
      getDouble("akka.actor.default-dispatcher.max-pool-size-factor") must equal(Some(8.0))
      getInt("akka.actor.default-dispatcher.task-queue-size") must equal(Some(-1))
      getString("akka.actor.default-dispatcher.task-queue-type") must equal(Some("linked"))
      getBool("akka.actor.default-dispatcher.allow-core-timeout") must equal(Some(true))
      getInt("akka.actor.default-dispatcher.mailbox-capacity") must equal(Some(-1))
      getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time") must equal(Some(10))
      getLong("akka.actor.dispatcher-shutdown-timeout") must equal(Some(1))
      getInt("akka.actor.default-dispatcher.throughput") must equal(Some(5))
      getInt("akka.actor.default-dispatcher.throughput-deadline-time") must equal(Some(-1))
      getBool("akka.actor.serialize-messages") must equal(Some(false))
      getInt("akka.actor.timeout") must equal(Some(5))
      getInt("akka.actor.throughput") must equal(Some(5))
      getInt("akka.actor.throughput-deadline-time") must equal(Some(-1))

      getString("akka.remote.layer") must equal(Some("akka.cluster.netty.NettyRemoteSupport"))
      getInt("akka.remote.server.port") must equal(Some(2552))
    }
  }
}
