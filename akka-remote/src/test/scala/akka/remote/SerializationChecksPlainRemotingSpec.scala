/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec

class SerializationChecksPlainRemotingSpec extends AkkaSpec {

  "Settings serialize-messages and serialize-creators" must {

    "be on for tests" in {
      system.settings.SerializeAllCreators must be(true)
      system.settings.SerializeAllMessages must be(true)
    }

  }

}
