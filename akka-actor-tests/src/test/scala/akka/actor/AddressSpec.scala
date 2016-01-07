/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.{ WordSpec, ShouldMatchers }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AddressSpec extends WordSpec with ShouldMatchers {

  "The Address factory methods" should {

    "fail if ipv6 address is not wrapped in brackets" in {
      intercept[IllegalArgumentException] {
        Address("tcp", "system", "0:0:0:0:0:0:0:1", 2551)
      }.getMessage should include("must be wrapped with []")
    }

    "not fail if ipv6 address is wrapped in brackets" in {
      Address("tcp", "system", "[0:0:0:0:0:0:0:1]", 2551)

    }

    "not fail with an ipv4 address" in {
      Address("tcp", "system", "192.168.0.1", 2551)
    }

    "not fail with a hostname address" in {
      Address("tcp", "system", "example.com", 2551)
    }
  }
}
