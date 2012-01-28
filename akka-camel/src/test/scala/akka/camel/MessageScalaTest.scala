/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import java.io.InputStream

import org.junit.Assert._

import org.apache.camel.NoTypeConversionAvailableException
import akka.camel.TestSupport.{ SharedCamelSystem, MessageSugar }
import org.scalatest.FunSuite

class MessageScalaTest extends FunSuite with SharedCamelSystem with MessageSugar {

  test("mustConvertDoubleBodyToString") {
    assertEquals("1.4", Message(1.4).bodyAs[String])
  }

  test("mustThrowExceptionWhenConvertingDoubleBodyToInputStream") {
    intercept[NoTypeConversionAvailableException] {
      Message(1.4).bodyAs[InputStream]
    }
  }

  test("mustReturnDoubleHeader") {
    val message = Message("test", Map("test" -> 1.4))
    assertEquals(1.4, message.header("test").get)
  }

  test("mustConvertDoubleHeaderToString") {
    val message = Message("test", Map("test" -> 1.4))
    assertEquals("1.4", message.headerAs[String]("test").get)
  }

  test("mustReturnSubsetOfHeaders") {
    val message = Message("test", Map("A" -> "1", "B" -> "2"))
    assertEquals(Map("B" -> "2"), message.headers(Set("B")))
  }

  test("mustTransformBodyAndPreserveHeaders") {
    assertEquals(
      Message("ab", Map("A" -> "1")),
      Message("a", Map("A" -> "1")).mapBody((body: String) ⇒ body + "b"))
  }

  test("mustConvertBodyAndPreserveHeaders") {
    assertEquals(
      Message("1.4", Map("A" -> "1")),
      Message(1.4, Map("A" -> "1")).withBodyAs[String])
  }

  test("mustSetBodyAndPreserveHeaders") {
    assertEquals(
      Message("test2", Map("A" -> "1")),
      Message("test1", Map("A" -> "1")).withBody("test2"))
  }

  test("mustSetHeadersAndPreserveBody") {
    assertEquals(
      Message("test1", Map("C" -> "3")),
      Message("test1", Map("A" -> "1")).withHeaders(Map("C" -> "3")))

  }

  test("mustAddHeaderAndPreserveBodyAndHeaders") {
    assertEquals(
      Message("test1", Map("A" -> "1", "B" -> "2")),
      Message("test1", Map("A" -> "1")).plusHeader("B" -> "2"))
  }

  test("mustAddHeadersAndPreserveBodyAndHeaders") {
    assertEquals(
      Message("test1", Map("A" -> "1", "B" -> "2")),
      Message("test1", Map("A" -> "1")).plusHeaders(Map("B" -> "2")))
  }

  test("mustRemoveHeadersAndPreserveBodyAndRemainingHeaders") {
    assertEquals(
      Message("test1", Map("A" -> "1")),
      Message("test1", Map("A" -> "1", "B" -> "2")).withoutHeader("B"))
  }
}
