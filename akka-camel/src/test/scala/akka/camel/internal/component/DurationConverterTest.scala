/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
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
    convertTo(classOf[Duration], "10 nanos") should ===(10 nanos)
  }

  "DurationTypeConverter must do the roundtrip" in {
    convertTo(classOf[Duration], (10 seconds).toString()) should ===(10 seconds)
  }

  "DurationTypeConverter must throw if invalid format" in {
    tryConvertTo(classOf[Duration], "abc nanos") should ===(null)

    intercept[TypeConversionException] {
      mandatoryConvertTo(classOf[Duration], "abc nanos") should ===(10 nanos)
    }.getValue should ===("abc nanos")
  }

  "DurationTypeConverter must throw if doesn't end with time unit" in {
    tryConvertTo(classOf[Duration], "10233") should ===(null)

    intercept[TypeConversionException] {
      mandatoryConvertTo(classOf[Duration], "10233") should ===(10 nanos)
    }.getValue should ===("10233")
  }

}

