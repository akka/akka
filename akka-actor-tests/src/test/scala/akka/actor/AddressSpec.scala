/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AddressSpec extends WordSpec with MustMatchers {

  val explicitErrorMessage = "The 2.1.3 documentation is available at http://doc.akka.io/docs/akka/2.1.3/"

  "An Address" must {

    "allow a correct protocol" in {
      Address("akka", "foo")
    }

    "disallow wrong protocol" in {
      val exception = evaluating { Address("whatever", "foo") } must produce[Exception]
      exception.getMessage.contains(explicitErrorMessage) must be(false)
    }

    "be explicit about link to docs when using protocol akka.tcp" in {
      val exception = evaluating { Address("akka.tcp", "foo") } must produce[Exception]
      exception.getMessage.contains(explicitErrorMessage) must be(true)
    }
  }
}
