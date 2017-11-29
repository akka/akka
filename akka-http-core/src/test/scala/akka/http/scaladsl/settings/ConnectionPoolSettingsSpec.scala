/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

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
  }

  def config(configString: String): ConnectionPoolSettings =
    ConnectionPoolSettings(configString)
}
