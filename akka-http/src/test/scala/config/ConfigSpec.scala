/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.testkit.AkkaSpec

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ConfigSpec extends AkkaSpec {

  "The default configuration file (i.e. akka-reference.conf)" should {
    "contain all configuration properties for akka-http that are used in code with their correct defaults" in {
      import application.config._
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
