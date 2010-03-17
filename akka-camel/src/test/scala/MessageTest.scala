package se.scalablesolutions.akka.camel

import java.io.InputStream

import org.apache.camel.NoTypeConversionAvailableException
import org.junit.Assert._
import org.scalatest.junit.JUnitSuite

import org.junit.Test

class MessageTest extends JUnitSuite {

  //
  // TODO: extend/rewrite unit tests
  // These tests currently only ensure proper functioning of basic features.
  //

  @Test def shouldConvertDoubleBodyToString = {
    CamelContextManager.init
    assertEquals("1.4", Message(1.4, null).bodyAs(classOf[String]))
  }

  @Test def shouldThrowExceptionWhenConvertingDoubleBodyToInputStream {
    CamelContextManager.init
    intercept[NoTypeConversionAvailableException] {
      Message(1.4, null).bodyAs(classOf[InputStream])
    }
  }

  @Test def shouldReturnSubsetOfHeaders = {
    val message = Message("test" , Map("A" -> "1", "B" -> "2"))
    assertEquals(Map("B" -> "2"), message.headers(Set("B")))
  }

  @Test def shouldTransformBodyAndPreserveHeaders = {
    assertEquals(
      Message("ab", Map("A" -> "1")),
      Message("a" , Map("A" -> "1")).transformBody[String](body => body + "b"))
  }

  @Test def shouldConvertBodyAndPreserveHeaders = {
    CamelContextManager.init
    assertEquals(
      Message("1.4", Map("A" -> "1")),
      Message(1.4  , Map("A" -> "1")).setBodyAs(classOf[String]))
  }

  @Test def shouldSetBodyAndPreserveHeaders = {
    assertEquals(
      Message("test2" , Map("A" -> "1")),
      Message("test1" , Map("A" -> "1")).setBody("test2"))
  }

  @Test def shouldSetHeadersAndPreserveBody = {
    assertEquals(
      Message("test1" , Map("C" -> "3")),
      Message("test1" , Map("A" -> "1")).setHeaders(Map("C" -> "3")))

  }

  @Test def shouldAddHeaderAndPreserveBodyAndHeaders = {
    assertEquals(
      Message("test1" , Map("A" -> "1", "B" -> "2")),
      Message("test1" , Map("A" -> "1")).addHeader("B" -> "2"))
  }

  @Test def shouldAddHeadersAndPreserveBodyAndHeaders = {
    assertEquals(
      Message("test1" , Map("A" -> "1", "B" -> "2")),
      Message("test1" , Map("A" -> "1")).addHeaders(Map("B" -> "2")))
  }

  @Test def shouldRemoveHeadersAndPreserveBodyAndRemainingHeaders = {
    assertEquals(
      Message("test1" , Map("A" -> "1")),
      Message("test1" , Map("A" -> "1", "B" -> "2")).removeHeader("B"))
  }

}