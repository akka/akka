/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import org.scalatest.{Matchers, WordSpec}

class AkkaVersionSpec extends WordSpec with Matchers {

  "The Akka version check" must {

    "succeed if version is ok" in {
      AkkaVersion.require("AkkaVersionSpec", Set("2.5.6"), "2.5.6")
      AkkaVersion.require("AkkaVersionSpec", Set("2.5.6"), "2.5.7")
      AkkaVersion.require("AkkaVersionSpec", Set("2.5.6", "2.4.19"), "2.4.19")
    }

    "fail if minor version is missing" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", Set("2.5.6"), "2.4.19")
      }
    }

    "fail if patch version is too low" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", Set("2.5.6"), "2.5.5")
      }
    }

    "fail if Akka version is SNAPSHOT or timestamped snapshot" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", Set("2.5.6"), "2.5-SNAPSHOT")
      }
    }

    "fail if required versions are invalid" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", Set("2.5-SNAPSHOT"), "2.5-SNAPSHOT")
      }
    }

  }

}
