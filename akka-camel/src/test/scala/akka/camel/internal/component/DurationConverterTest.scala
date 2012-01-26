/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.matchers.MustMatchers
import akka.util.duration._
import akka.util.Duration
import org.scalatest.WordSpec

class DurationConverterTest extends WordSpec with MustMatchers {
  import DurationTypeConverter._

  "DurationTypeConverter must convert '10 nanos'" in {
    convertTo(classOf[Duration], "10 nanos") must be(10 nanos)
  }

  "DurationTypeConverter must do the roundtrip" in {
    convertTo(classOf[Duration], DurationTypeConverter.toString(10 seconds)) must be(10 seconds)
  }

  "DurationTypeConverter must throw if invalid format" in {
    intercept[Exception] {
      convertTo(classOf[Duration], "abc nanos") must be(10 nanos)
    }
  }

  "DurationTypeConverter must throw if doesn't end with nanos" in {
    intercept[Exception] {
      convertTo(classOf[Duration], "10233") must be(10 nanos)
    }
  }

}

