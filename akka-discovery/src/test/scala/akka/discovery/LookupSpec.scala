/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
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

  "Lookup.parseSrv" should {

    "generate a SRV Lookup from a SRV String" in {
      val name = "_portName._protocol.serviceName.local"
      val lookup = Lookup.parseSrv(name)
      lookup.serviceName shouldBe "serviceName.local"
      lookup.portName.value shouldBe "portName"
      lookup.protocol.value shouldBe "protocol"
    }

    "throw an IllegalArgumentException when passing a 'null' SRV String" in {
      assertThrows[NullPointerException] {
        Lookup.parseSrv(null)
      }
    }

    "throw an IllegalArgumentException when passing an empty SRV String" in {
      assertThrows[IllegalArgumentException] {
        Lookup.parseSrv("")
      }
    }

    "throw an IllegalArgumentException for any non-conforming SRV String" in {
      noSrvLookups.foreach { str ⇒
        withClue(s"parsing '$str'") {
          assertThrows[IllegalArgumentException] {
            Lookup.parseSrv(str)
          }
        }
      }
    }

    "throw an IllegalArgumentException for any SRV with invalid domain names" in {
      srvWithInvalidDomainNames.foreach { str ⇒
        withClue(s"parsing '$str'") {
          assertThrows[IllegalArgumentException] {
            Lookup.parseSrv(str)
          }
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

    "return false if domain part in SRV String is an invalid domain name" in {
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
