/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.scalatest.FreeSpec
import akka.camel.DangerousStuff._
import org.scalatest.matchers.ShouldMatchers

class DangerousStuffTest extends FreeSpec with ShouldMatchers{

  "Safe" -{
    "executes block" in {
      var executed = false
      safe{ executed = true }
      executed should  be (true)
    }

    "swallows exception" in {
      safe(throw new Exception)
    }
  }
  
  "try_ otherwise" - {

    "runs otherwise and throws exception when the first block fails" in{
      var otherwiseCalled = false
      intercept[Exception]{
        try_(throw new Exception) otherwise (otherwiseCalled=true)
      }
      otherwiseCalled should be (true)
    }

    "doesnt throw exception from otherwise" in{
      intercept[RuntimeException] {
        try_(throw new RuntimeException("e1")) otherwise (throw new RuntimeException("e2"))
      }.getMessage should be ("e1")
    }


    "doesnt run otherwise if first block doesnt fail" in{
      var otherwiseCalled = false
      try_(2+2) otherwise (otherwiseCalled=true)
      otherwiseCalled should be (false)
    }
  }
  
}