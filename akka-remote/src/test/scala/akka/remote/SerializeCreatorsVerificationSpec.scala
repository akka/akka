/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit.AkkaSpec

class SerializeCreatorsVerificationSpec extends AkkaSpec {

  "serialize-creators must be on" in {
    system.settings.SerializeAllCreators must be === true
  }

}