/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }

class RemoteSettingsSpec extends WordSpec with Matchers {

  "Remote settings" must {
    "default akka.remote.log-frame-size-exceeding to off" in {
      new RemoteSettings(ConfigFactory.load()).LogFrameSizeExceeding shouldEqual None
    }

    "parse akka.remote.log-frame-size-exceeding  value as bytes" in {
      new RemoteSettings(ConfigFactory.parseString("akka.remote.log-frame-size-exceeding = 100b").withFallback(ConfigFactory.load())).LogFrameSizeExceeding shouldEqual Some(100)
    }
  }

}
