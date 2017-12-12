/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.testkit.AkkaSpec

class PreviewServerSettingsSpec extends AkkaSpec {

  def compileOnlySpec(body: ⇒ Unit) = ()

  "PreviewServerSettings" should {
    "compile when set programatically" in compileOnlySpec {
      import akka.http.scaladsl.settings.ServerSettings
      import akka.http.scaladsl.settings.PreviewServerSettings
      val serverSettings: ServerSettings =
        ServerSettings(system)
          .withPreviewServerSettings(PreviewServerSettings(system).withEnableHttp2(true))
          .withRemoteAddressHeader(true)
    }
    "work get right defaults" in {
      val it: PreviewServerSettings = PreviewServerSettings(system)
      it.enableHttp2 should ===(false)
    }
  }
}
