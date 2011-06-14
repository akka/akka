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
    "contain all configuration properties for akka-http that are used in code with their correct defaults" in {
      import Config.config._

      getBool("akka.http.connection-close") must equal(Some(true))
      getString("akka.http.expired-header-name") must equal(Some("Async-Timeout"))
      getString("akka.http.hostname") must equal(Some("localhost"))
      getString("akka.http.expired-header-value") must equal(Some("expired"))
      getInt("akka.http.port") must equal(Some(9998))
      getBool("akka.http.root-actor-builtin") must equal(Some(true))
      getString("akka.http.root-actor-id") must equal(Some("_httproot"))
      getLong("akka.http.timeout") must equal(Some(1000))
    }
  }
}
