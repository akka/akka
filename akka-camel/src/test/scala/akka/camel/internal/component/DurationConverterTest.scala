/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.matchers.MustMatchers
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import org.scalatest.WordSpec
import org.apache.camel.{ TypeConversionException, NoTypeConversionAvailableException }

class DurationConverterSpec extends WordSpec with MustMatchers {
  import DurationTypeConverter._

  "DurationTypeConverter must convert '10 nanos'" in {
    convertTo(classOf[Duration], "10 nanos") must be(10 nanos)
  }

  "DurationTypeConverter must do the roundtrip" in {
    convertTo(classOf[Duration], (10 seconds).toString()) must be(10 seconds)
  }

  "DurationTypeConverter must throw if invalid format" in {
    tryConvertTo(classOf[Duration], "abc nanos") must be === null

    intercept[TypeConversionException] {
      mandatoryConvertTo(classOf[Duration], "abc nanos") must be(10 nanos)
    }.getValue must be === "abc nanos"
  }

  "DurationTypeConverter must throw if doesn't end with time unit" in {
    tryConvertTo(classOf[Duration], "10233") must be === null

    intercept[TypeConversionException] {
      mandatoryConvertTo(classOf[Duration], "10233") must be(10 nanos)
    }.getValue must be === "10233"
  }

}

