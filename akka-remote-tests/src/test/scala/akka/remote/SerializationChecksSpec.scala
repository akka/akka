/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.testkit.AkkaSpec

class SerializationChecksSpec extends AkkaSpec {

  "Settings serialize-messages" must {

    "be on for tests" in {
      system.settings.SerializeAllMessages should ===(true)
    }

  }

}
