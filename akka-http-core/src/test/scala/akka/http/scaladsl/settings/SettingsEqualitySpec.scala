/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import com.typesafe.config.ConfigFactory

import org.scalatest.Matchers
import org.scalatest.WordSpec

class SettingsEqualitySpec extends WordSpec with Matchers {

  val config = ConfigFactory.parseString("""
    akka.http.routing {
      verbose-error-messages = off
      file-get-conditional = on
      render-vanity-footer = yes
      range-coalescing-threshold = 80
      range-count-limit = 16
      decode-max-bytes-per-chunk = 1m
      file-io-dispatcher = ${akka.stream.blocking-io-dispatcher}
    }
  """).withFallback(ConfigFactory.load).resolve

  "equality" should {
    "hold for ConnectionPoolSettings" in {
      val s1 = ConnectionPoolSettings(config)
      val s2 = ConnectionPoolSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ConnectionPoolSettings(")
    }

    "hold for ParserSettings" in {
      val s1 = ParserSettings(config)
      val s2 = ParserSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ParserSettings(")
    }

    "hold for ClientConnectionSettings" in {
      val s1 = ClientConnectionSettings(config)
      val s2 = ClientConnectionSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ClientConnectionSettings(")
    }

    "hold for RoutingSettings" in {
      val s1 = RoutingSettings(config)
      val s2 = RoutingSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("RoutingSettings(")
    }

    "hold for ServerSettings" in {
      val s1 = ServerSettings(config)
      val s2 = ServerSettings(config)

      s1 shouldBe s2
      s1.toString should startWith("ServerSettings(")
    }
  }

}
