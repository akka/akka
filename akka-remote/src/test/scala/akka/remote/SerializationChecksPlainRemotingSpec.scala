/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec

class SerializationChecksPlainRemotingSpec extends AkkaSpec {

  "Settings serialize-messages and serialize-creators" must {

    "be on for tests" in {
      system.settings.SerializeAllCreators should ===(true)
      system.settings.SerializeAllMessages should ===(true)
    }

  }

}
