/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers

@RunWith(classOf[JUnitRunner])
class ConfigSpec extends WordSpec with MustMatchers {

  "The default configuration file (i.e. akka-reference.conf)" should {
    "contain all configuration properties for akka-remote that are used in code with their correct defaults" in {
      import Config.config._

      getInt("akka.remote.client.message-frame-size") must equal(Some(1048576))
      getInt("akka.remote.client.read-timeout") must equal(Some(10))
      getInt("akka.remote.client.reap-futures-delay") must equal(Some(5))
      getInt("akka.remote.client.reconnect-delay") must equal(Some(5))
      getInt("akka.remote.client.reconnection-time-window") must equal(Some(600))

      getString("akka.remote.compression-scheme") must equal(Some("zlib"))
      getString("akka.remote.secure-cookie") must equal(Some(""))

      getInt("akka.remote.server.backlog") must equal(Some(4096))
      getInt("akka.remote.server.connection-timeout") must equal(Some(1))
      getString("akka.remote.server.hostname") must equal(Some("localhost"))
      getInt("akka.remote.server.message-frame-size") must equal(Some(1048576))
      getInt("akka.remote.server.port") must equal(Some(2552))
      getBool("akka.remote.server.require-cookie") must equal(Some(false))
      getBool("akka.remote.server.untrusted-mode") must equal(Some(false))

      getBool("akka.remote.ssl.debug") must equal(Some(false))
      getBool("akka.remote.ssl.service") must equal(Some(false))
      getInt("akka.remote.zlib-compression-level") must equal(Some(6))
    }
  }
}
