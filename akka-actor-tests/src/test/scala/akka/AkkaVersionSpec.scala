/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import org.scalatest.{ Matchers, WordSpec }

class AkkaVersionSpec extends WordSpec with Matchers {

  "The Akka version check" must {

    "succeed if version is ok" in {
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.6")
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.7")
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.6.0")
    }

    "succeed if version is RC and ok" in {
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.7-RC10")
    }

    "fail if version is RC and not ok" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.6-RC1")
      }
    }

    "succeed if version is milestone and ok" in {
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.7-M10")
    }

    "fail if version is milestone and not ok" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.6-M1")
      }
    }

    "fail if major version is different" in {
      // because not bincomp
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", "2.5.6", "3.0.0")
      }
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", "2.5.6", "1.0.0")
      }
    }

    "fail if minor version is too low" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.4.19")
      }
    }

    "fail if patch version is too low" in {
      intercept[UnsupportedAkkaVersion] {
        AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5.5")
      }
    }

    "succeed if Akka version is SNAPSHOT" in {
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5-SNAPSHOT")
    }

    "succeed if Akka version is timestamped SNAPSHOT" in {
      AkkaVersion.require("AkkaVersionSpec", "2.5.6", "2.5-20180109-133700")
    }

    "silently comply if current version is incomprehensible" in {
      // because we may want to release with weird numbers for some reason
      AkkaVersion.require("nonsense", "2.5.6", "nonsense")
    }

    "fail if fed incomprehensible requirement" in {
      intercept[IllegalArgumentException] {
        AkkaVersion.require("AkkaVersionSpec", "nonsense", "2.5.6")
      }
    }

  }

}
