/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers

@RunWith(classOf[JUnitRunner])
class ConfigSpec extends WordSpec with MustMatchers {

  "The default configuration file (i.e. akka-reference.conf)" should {
    "contain all configuration properties for akka-actor that are used in code with their correct defaults" in {
      import Config.config._

      getList("akka.boot") must equal(Nil)
      getString("akka.time-unit") must equal(Some("seconds"))
      getString("akka.version") must equal(Some("1.3.1"))

      getString("akka.actor.default-dispatcher.type") must equal(Some("GlobalExecutorBasedEventDriven"))
      getInt("akka.actor.default-dispatcher.keep-alive-time") must equal(Some(60))
      getDouble("akka.actor.default-dispatcher.core-pool-size-factor") must equal(Some(1.0))
      getDouble("akka.actor.default-dispatcher.max-pool-size-factor") must equal(Some(4.0))
      getInt("akka.actor.default-dispatcher.executor-bounds") must equal(Some(-1))
      getInt("akka.actor.default-dispatcher.task-queue-size") must equal(Some(-1))
      getString("akka.actor.default-dispatcher.task-queue-type") must equal(Some("linked"))
      getBool("akka.actor.default-dispatcher.allow-core-timeout") must equal(Some(true))
      getString("akka.actor.default-dispatcher.rejection-policy") must equal(Some("sane"))
      getInt("akka.actor.default-dispatcher.mailbox-capacity") must equal(Some(-1))
      getInt("akka.actor.default-dispatcher.mailbox-push-timeout-time") must equal(Some(10))
      getLong("akka.actor.dispatcher-shutdown-timeout") must equal(Some(1))
      getInt("akka.actor.default-dispatcher.throughput") must equal(Some(5))
      getInt("akka.actor.default-dispatcher.throughput-deadline-time") must equal(Some(-1))
      getBool("akka.actor.serialize-messages") must equal(Some(false))
      getInt("akka.actor.timeout") must equal(Some(5))
      getInt("akka.actor.throughput") must equal(Some(5))
      getInt("akka.actor.throughput-deadline-time") must equal(Some(-1))

      getString("akka.remote.layer") must equal(Some("akka.remote.netty.NettyRemoteSupport"))
      getString("akka.remote.server.hostname") must equal(Some("localhost"))
      getInt("akka.remote.server.port") must equal(Some(2552))
    }
  }
}
