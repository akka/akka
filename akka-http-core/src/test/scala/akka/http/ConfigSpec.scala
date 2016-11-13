/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http

import akka.actor.ActorSystem
import akka.testkit.AkkaSpec
import org.scalatest.Assertions
import com.typesafe.config.ConfigFactory
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.scaladsl.model.headers.Server
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ServerSettings }

class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference(ActorSystem.findClassLoader())) with Assertions {

  "The default configuration file (i.e. reference.conf)" must {
    "include the generated version file (i.e. akka-http-version.conf)" in {
      val settings = system.settings
      val config = settings.config

      config.getString("akka.http.version") should ===(Version.current)

      val versionString = "akka-http/" + Version.current
      val serverSettings = ServerSettings(system)
      serverSettings.serverHeader should ===(Some(Server(versionString)))

      val clientConnectionSettings = ClientConnectionSettings(system)
      clientConnectionSettings.userAgentHeader should ===(Some(`User-Agent`(versionString)))
    }
  }
}
