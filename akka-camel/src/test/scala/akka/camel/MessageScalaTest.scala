package akka.camel

import java.io.InputStream

import org.junit.Assert._

import org.apache.camel.NoTypeConversionAvailableException
import akka.camel.TestSupport.{SharedCamelSystem, MessageSugar}
import org.scalatest.FunSuite


class MessageScalaTest extends FunSuite with SharedCamelSystem with MessageSugar{


  test("shouldConvertDoubleBodyToString")  {
    assertEquals("1.4", Message(1.4).bodyAs[String])
  }

  test("shouldThrowExceptionWhenConvertingDoubleBodyToInputStream") {
    intercept[NoTypeConversionAvailableException] {
      Message(1.4).bodyAs[InputStream]
    }
  }

  test("shouldReturnDoubleHeader"){
    val message = Message("test" , Map("test" -> 1.4))
    assertEquals(1.4, message.header("test"))
  }

  test("shouldConvertDoubleHeaderToString"){
    val message = Message("test" , Map("test" -> 1.4))
    assertEquals("1.4", message.headerAs[String]("test"))
  }

  test("shouldReturnSubsetOfHeaders"){
    val message = Message("test" , Map("A" -> "1", "B" -> "2"))
    assertEquals(Map("B" -> "2"), message.headers(Set("B")))
  }

  test("shouldTransformBodyAndPreserveHeaders"){
    assertEquals(
      Message("ab", Map("A" -> "1")),
      Message("a" , Map("A" -> "1")).transformBody((body: String) => body + "b"))
  }

  test("shouldConvertBodyAndPreserveHeaders"){
    assertEquals(
      Message("1.4", Map("A" -> "1")),
      Message(1.4  , Map("A" -> "1")).setBodyAs[String])
  }

  test("shouldSetBodyAndPreserveHeaders"){
    assertEquals(
      Message("test2" , Map("A" -> "1")),
      Message("test1" , Map("A" -> "1")).setBody("test2"))
  }

  test("shouldSetHeadersAndPreserveBody"){
    assertEquals(
      Message("test1" , Map("C" -> "3")),
      Message("test1" , Map("A" -> "1")).setHeaders(Map("C" -> "3")))

  }

  test("shouldAddHeaderAndPreserveBodyAndHeaders"){
    assertEquals(
      Message("test1" , Map("A" -> "1", "B" -> "2")),
      Message("test1" , Map("A" -> "1")).addHeader("B" -> "2"))
  }

  test("shouldAddHeadersAndPreserveBodyAndHeaders"){
    assertEquals(
      Message("test1" , Map("A" -> "1", "B" -> "2")),
      Message("test1" , Map("A" -> "1")).addHeaders(Map("B" -> "2")))
  }

  test("shouldRemoveHeadersAndPreserveBodyAndRemainingHeaders"){
    assertEquals(
      Message("test1" , Map("A" -> "1")),
      Message("test1" , Map("A" -> "1", "B" -> "2")).removeHeader("B"))
  }
}
