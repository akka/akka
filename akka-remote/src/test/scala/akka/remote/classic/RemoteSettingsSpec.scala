/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import scala.annotation.nowarn
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.remote.RemoteSettings

@nowarn("msg=deprecated")
class RemoteSettingsSpec extends AnyWordSpec with Matchers {

  "Remote settings" must {
    "default akka.remote.classic.log-frame-size-exceeding to off" in {
      new RemoteSettings(ConfigFactory.load()).LogFrameSizeExceeding shouldEqual None
    }

    "parse akka.remote.classic.log-frame-size-exceeding  value as bytes" in {
      new RemoteSettings(
        ConfigFactory
          .parseString("akka.remote.classic.log-frame-size-exceeding = 100b")
          .withFallback(ConfigFactory.load())).LogFrameSizeExceeding shouldEqual Some(100)
    }
  }

}
