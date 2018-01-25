/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.testkit.AkkaSpec
import akka.http.scaladsl.model.headers.`User-Agent`

class ConnectionPoolSettingsSpec extends AkkaSpec {
  "ConnectionPoolSettings" should {
    "use akka.http.client settings by default" in {
      val settings = config(
        """
          akka.http.client.user-agent-header = "serva/0.0"
        """)

      settings.connectionSettings.userAgentHeader shouldEqual Some(`User-Agent`.parseFromValueString("serva/0.0").right.get)
    }
    "allow overriding client settings with akka.http.host-connection-pool.client" in {
      val settings = config(
        """
          akka.http.client.request-header-size-hint = 1024
          akka.http.client.user-agent-header = "serva/0.0"
          akka.http.host-connection-pool.client.user-agent-header = "serva/5.7"
        """)

      settings.connectionSettings.userAgentHeader shouldEqual Some(`User-Agent`.parseFromValueString("serva/5.7").right.get)
      settings.connectionSettings.requestHeaderSizeHint shouldEqual 1024 // still fall back
    }
    "allow max-open-requests = 1" in {
      config("akka.http.host-connection-pool.max-open-requests = 1").maxOpenRequests should be(1)
    }
    "produce a nice error message when max-open-requests" in {
      expectError("akka.http.host-connection-pool.max-open-requests = 10") should include("Perhaps try 8 or 16")
      expectError("akka.http.host-connection-pool.max-open-requests = 100") should include("Perhaps try 64 or 128")
      expectError("akka.http.host-connection-pool.max-open-requests = 1000") should include("Perhaps try 512 or 1024")
    }
    def expectError(configString: String): String = Try(config(configString)) match {
      case Failure(cause) ⇒ cause.getMessage
      case Success(_)     ⇒ fail("Expected a failure when max-open-requests is not a power of 2")
    }
  }

  def config(configString: String): ConnectionPoolSettings =
    ConnectionPoolSettings(configString)
}
