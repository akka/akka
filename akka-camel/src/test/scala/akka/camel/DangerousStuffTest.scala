/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.scalatest.FreeSpec
import akka.camel.DangerousStuff._
import org.scalatest.matchers.MustMatchers

class DangerousStuffTest extends FreeSpec with MustMatchers {

  "Safe" - {
    "executes block" in {
      var executed = false
      safe { executed = true }
      executed must be(true)
    }

    "swallows exception" in {
      safe(throw new Exception)
    }
  }

  "try_ otherwise" - {

    "runs otherwise and throws exception when the first block fails" in {
      var otherwiseCalled = false
      intercept[Exception] {
        try_(throw new Exception) otherwise (otherwiseCalled = true)
      }
      otherwiseCalled must be(true)
    }

    "doesnt throw exception from otherwise" in {
      intercept[RuntimeException] {
        try_(throw new RuntimeException("e1")) otherwise (throw new RuntimeException("e2"))
      }.getMessage must be("e1")
    }

    "doesnt run otherwise if first block doesnt fail" in {
      var otherwiseCalled = false
      try_(2 + 2) otherwise (otherwiseCalled = true)
      otherwiseCalled must be(false)
    }
  }

}