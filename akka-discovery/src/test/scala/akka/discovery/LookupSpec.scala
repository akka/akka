/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery

import org.scalatest.{ Matchers, OptionValues, WordSpec }

class LookupSpec extends WordSpec with Matchers with OptionValues {

  // SRV strings with invalid domain names
  // should fail to build lookups
  val srvWithInvalidDomainNames = List(
    "_portName._protocol.service_name.local",
    "_portName._protocol.servicename,local",
    "_portName._protocol.servicename.local-",
    "_portName._protocol.-servicename.local")

  // No SRV that should result in simple A/AAAA lookups
  val noSrvLookups = List(
    "portName.protocol.serviceName.local",
    "serviceName.local",
    "_portName.serviceName",
    "_serviceName.local",
    "_serviceName,local",
    "-serviceName.local",
    "serviceName.local-")

  "Lookup.fromString" should {

    "generate a SRV Lookup from a SRV String" in {
      val name = "_portName._protocol.serviceName.local"
      val lookup = Lookup.fromString(name)
      lookup.serviceName shouldBe "serviceName.local"
      lookup.portName.value shouldBe "portName"
      lookup.protocol.value shouldBe "protocol"
    }

    "generate a A/AAAA from any non-conforming SRV String" in {
      (noSrvLookups ++ srvWithInvalidDomainNames).foreach { str ⇒
        withClue(s"parsing '$str'") {
          val lookup = Lookup.fromString(str)
          lookup.serviceName shouldBe str
          lookup.portName shouldBe empty
          lookup.protocol shouldBe empty
        }
      }
    }

  }

  "Lookup.isValidSrv" should {

    "return true for any conforming SRV String" in {
      Lookup.isValidSrv("_portName._protocol.serviceName.local") shouldBe true
    }

    "return false for any non-conforming SRV String" in {
      noSrvLookups.foreach { str ⇒
        withClue(s"checking '$str'") {
          Lookup.isValidSrv(str) shouldBe false
        }
      }
    }

    "return false for if domain part in SRV String is an invalid domain name" in {
      srvWithInvalidDomainNames.foreach { str ⇒
        withClue(s"checking '$str'") {
          Lookup.isValidSrv(str) shouldBe false
        }
      }
    }

    "return false for empty SRV String" in {
      Lookup.isValidSrv("") shouldBe false
    }

    "return false for 'null' SRV String" in {
      Lookup.isValidSrv(null) shouldBe false
    }

    "return true for a SRV with valid domain name" in {
      Lookup.isValidSrv("_portName._protocol.serviceName.local") shouldBe true
    }

  }
}
