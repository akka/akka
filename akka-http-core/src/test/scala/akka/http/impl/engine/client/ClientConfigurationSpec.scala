/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import akka.stream.testkit.AkkaSpec

class ClientConfigurationSpec extends AkkaSpec {
  "Reference configurations" should {
    for {
      (first, second) ← "akka.http.client" -> "akka.http.host-connection-pool.client" ::
        "akka.http.client.parsing" -> "akka.http.host-connection-pool.client.parsing" ::
        "akka.http.server.parsing" -> "akka.http.client.parsing" ::
        Nil
    } s"be consistent for: `$first` and `$second`" in {
      configShouldBeEqual("akka.http.client.parsing", "akka.http.host-connection-pool.client.parsing")
    }
  }

  private def configShouldBeEqual(first: String, second: String): Unit = {
    val config = system.settings.config
    withClue(s"Config [$first] did not equal [$second]!") {
      config.getConfig(first) should ===(config.getConfig(second))
    }
  }
}