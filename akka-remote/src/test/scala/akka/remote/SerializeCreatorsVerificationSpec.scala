/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote

import akka.testkit.AkkaSpec

class SerializeCreatorsVerificationSpec extends AkkaSpec {

  "serialize-creators should be on" in {
    system.settings.SerializeAllCreators should ===(true)
  }

}