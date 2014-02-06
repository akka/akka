/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import language.postfixOps

import org.scalatest.Matchers
import scala.concurrent.duration._
import org.scalatest.WordSpec
import org.apache.camel.TypeConversionException

class DurationConverterSpec extends WordSpec with Matchers {
  import DurationTypeConverter._

  "DurationTypeConverter must convert '10 nanos'" in {
    convertTo(classOf[Duration], "10 nanos") should be(10 nanos)
  }

  "DurationTypeConverter must do the roundtrip" in {
    convertTo(classOf[Duration], (10 seconds).toString()) should be(10 seconds)
  }

  "DurationTypeConverter must throw if invalid format" in {
    tryConvertTo(classOf[Duration], "abc nanos") should be(null)

    intercept[TypeConversionException] {
      mandatoryConvertTo(classOf[Duration], "abc nanos") should be(10 nanos)
    }.getValue should be("abc nanos")
  }

  "DurationTypeConverter must throw if doesn't end with time unit" in {
    tryConvertTo(classOf[Duration], "10233") should be(null)

    intercept[TypeConversionException] {
      mandatoryConvertTo(classOf[Duration], "10233") should be(10 nanos)
    }.getValue should be("10233")
  }

}

