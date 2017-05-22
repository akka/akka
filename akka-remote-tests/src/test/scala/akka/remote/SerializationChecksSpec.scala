/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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
