/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.testkit.AkkaSpec

class SerializationChecksSpec extends AkkaSpec {

  "Settings serialize-messages and serialize-creators" must {

    "be on for tests" in {
      system.settings.SerializeAllCreators should ===(true)
      system.settings.SerializeAllMessages should ===(true)
    }

  }

}
