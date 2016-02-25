/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

class HttpConfigurationSpec extends AkkaSpec {

  val On = true
  val Off = false

  "Reference configurations" should {
    "have default client and server `parsing` settings" in {
      ServerSettings(system).parserSettings.toString should ===(ClientConnectionSettings(system).parserSettings.toString)
    }
    "have default client and pool `parsing` settings" in {
      ServerSettings(system).parserSettings.toString should ===(ConnectionPoolSettings(system).connectionSettings.parserSettings.toString)
    }
    "have default client and pool `client` settings" in {
      ClientConnectionSettings(system).toString should ===(ConnectionPoolSettings(system).connectionSettings.toString)
    }

    "override value from `akka.http.parsing` by setting `akka.http.client.parsing`" in {
      configuredSystem("""akka.http.client.parsing.illegal-header-warnings = off""") { sys ⇒
        val client = ClientConnectionSettings(sys)
        client.parserSettings.illegalHeaderWarnings should ===(Off)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.parserSettings.illegalHeaderWarnings should ===(Off)

        val server = ServerSettings(sys)
        server.parserSettings.illegalHeaderWarnings should ===(On)
      }
    }

    "override `akka.http.parsing` by setting `akka.http.host-connection-pool.client.parsing` setting" in {
      configuredSystem("""akka.http.host-connection-pool.client.parsing.illegal-header-warnings = off""") { sys ⇒
        val client = ClientConnectionSettings(sys)
        client.parserSettings.illegalHeaderWarnings should ===(On)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.parserSettings.illegalHeaderWarnings should ===(Off)

        val server = ServerSettings(sys)
        server.parserSettings.illegalHeaderWarnings should ===(On)
      }
    }

    "set `akka.http.host-connection-pool.client.idle-timeout` only" in {
      configuredSystem("""akka.http.host-connection-pool.client.idle-timeout = 1337s""") { sys ⇒
        import scala.concurrent.duration._

        val client = ClientConnectionSettings(sys)
        client.idleTimeout should ===(60.seconds)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.idleTimeout should ===(1337.seconds)

        val server = ServerSettings(sys)
        server.idleTimeout should ===(60.seconds) // no change, default akka.http.server.idle-timeout
      }
    }
    "set `akka.http.server.idle-timeout` only" in {
      configuredSystem("""akka.http.server.idle-timeout = 1337s""") { sys ⇒
        import scala.concurrent.duration._

        val client = ClientConnectionSettings(sys)
        client.idleTimeout should ===(60.seconds)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.idleTimeout should ===(60.seconds)

        val server = ServerSettings(sys)
        server.idleTimeout should ===(1337.seconds)
      }
    }

    "change parser settings for all by setting `akka.http.parsing`" in {
      configuredSystem("""akka.http.parsing.illegal-header-warnings = off""") { sys ⇒
        val client = ClientConnectionSettings(sys)
        client.parserSettings.illegalHeaderWarnings should ===(Off)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.parserSettings.illegalHeaderWarnings should ===(Off)

        val server = ServerSettings(sys)
        server.parserSettings.illegalHeaderWarnings should ===(Off)
      }
    }

    "change parser settings for all by setting `akka.http.parsing`, unless client/server override it" in {
      configuredSystem("""
        akka.http {
          parsing.illegal-header-warnings = off
          server.parsing.illegal-header-warnings = on
          client.parsing.illegal-header-warnings = on // also affects host-connection-pool.client
        }""") { sys ⇒
        val client = ClientConnectionSettings(sys)
        client.parserSettings.illegalHeaderWarnings should ===(On)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.parserSettings.illegalHeaderWarnings should ===(On)

        val server = ServerSettings(sys)
        server.parserSettings.illegalHeaderWarnings should ===(On)
      }
    }

    "change parser settings for all by setting `akka.http.parsing`, unless all override it" in {
      configuredSystem("""
        akka.http {
          parsing.illegal-header-warnings = off
          server.parsing.illegal-header-warnings = on
          client.parsing.illegal-header-warnings = on
          host-connection-pool.client.parsing.illegal-header-warnings = off
        }""") { sys ⇒
        val client = ClientConnectionSettings(sys)
        client.parserSettings.illegalHeaderWarnings should ===(On)

        val pool = ConnectionPoolSettings(sys)
        pool.connectionSettings.parserSettings.illegalHeaderWarnings should ===(Off)

        val server = ServerSettings(sys)
        server.parserSettings.illegalHeaderWarnings should ===(On)
      }
    }
  }

  def configuredSystem(overrides: String)(block: ActorSystem ⇒ Unit) = {
    val config = ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load())
    // we go via ActorSystem in order to hit the settings caching infrastructure
    val sys = ActorSystem("config-testing", config)
    try block(sys) finally sys.terminate()
  }

}