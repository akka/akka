/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec

class SerializationChecksSpec extends AkkaSpec {

  "Settings serialize-messages and serialize-creators" must {

    "be on for tests" in {
      system.settings.SerializeAllCreators should be(true)
      system.settings.SerializeAllMessages should be(true)
    }

  }

}
