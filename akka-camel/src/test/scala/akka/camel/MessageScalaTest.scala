/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel

import java.io.InputStream
import org.apache.camel.NoTypeConversionAvailableException
import akka.camel.TestSupport.{ SharedCamelSystem }
import org.scalatest.FunSuite
import org.scalatest.Matchers
import org.apache.camel.converter.stream.InputStreamCache

class MessageScalaTest extends FunSuite with Matchers with SharedCamelSystem {
  implicit def camelContext = camel.context
  test("mustConvertDoubleBodyToString") {
    CamelMessage(1.4, Map.empty).bodyAs[String] should ===("1.4")
  }

  test("mustThrowExceptionWhenConvertingDoubleBodyToInputStream") {
    intercept[NoTypeConversionAvailableException] {
      CamelMessage(1.4, Map.empty).bodyAs[InputStream]
    }
  }

  test("mustConvertDoubleHeaderToString") {
    val message = CamelMessage("test", Map("test" -> 1.4))
    message.headerAs[String]("test").get should ===("1.4")
  }

  test("mustReturnSubsetOfHeaders") {
    val message = CamelMessage("test", Map("A" -> "1", "B" -> "2"))
    message.headers(Set("B")) should ===(Map("B" -> "2"))
  }

  test("mustTransformBodyAndPreserveHeaders") {
    CamelMessage("a", Map("A" -> "1")).mapBody((body: String) => body + "b") should ===(
      CamelMessage("ab", Map("A" -> "1")))
  }

  test("mustConvertBodyAndPreserveHeaders") {
    CamelMessage(1.4, Map("A" -> "1")).withBodyAs[String] should ===(CamelMessage("1.4", Map("A" -> "1")))
  }

  test("mustSetBodyAndPreserveHeaders") {
    CamelMessage("test1", Map("A" -> "1")).copy(body = "test2") should ===(CamelMessage("test2", Map("A" -> "1")))
  }

  test("mustSetHeadersAndPreserveBody") {
    CamelMessage("test1", Map("A" -> "1")).copy(headers = Map("C" -> "3")) should ===(
      CamelMessage("test1", Map("C" -> "3")))
  }

  test("mustBeAbleToReReadStreamCacheBody") {
    val msg = CamelMessage(new InputStreamCache("test1".getBytes("utf-8")), Map.empty)
    msg.bodyAs[String] should ===("test1")
    // re-read
    msg.bodyAs[String] should ===("test1")
  }
}
