package akka.camel

import java.io.InputStream

import org.junit.Assert._
import org.junit.Test

import org.scalatest.junit.JUnitSuite
import org.apache.camel.NoTypeConversionAvailableException
import akka.camel.TestSupport.{SharedCamelSystem, MessageSugar}


class MessageScalaTest extends JUnitSuite with SharedCamelSystem with MessageSugar{


  @Test def shouldConvertDoubleBodyToString = {
    assertEquals("1.4", Message(1.4).bodyAs[String])
  }

  @Test def shouldThrowExceptionWhenConvertingDoubleBodyToInputStream {
    intercept[NoTypeConversionAvailableException] {
      Message(1.4).bodyAs[InputStream]
    }
  }

  @Test def shouldReturnDoubleHeader = {
    val message = Message("test" , Map("test" -> 1.4))
    assertEquals(1.4, message.header("test"))
  }

  @Test def shouldConvertDoubleHeaderToString = {
    val message = Message("test" , Map("test" -> 1.4))
    assertEquals("1.4", message.headerAs[String]("test"))
  }

  @Test def shouldReturnSubsetOfHeaders = {
    val message = Message("test" , Map("A" -> "1", "B" -> "2"))
    assertEquals(Map("B" -> "2"), message.headers(Set("B")))
  }

  @Test def shouldTransformBodyAndPreserveHeaders = {
    assertEquals(
      Message("ab", Map("A" -> "1")),
      Message("a" , Map("A" -> "1")).transformBody((body: String) => body + "b"))
  }

  @Test def shouldConvertBodyAndPreserveHeaders = {
    assertEquals(
      Message("1.4", Map("A" -> "1")),
      Message(1.4  , Map("A" -> "1")).setBodyAs[String])
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
