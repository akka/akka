package se.scalablesolutions.akka.camel.service

import java.io.InputStream

import org.apache.camel.NoTypeConversionAvailableException
import org.junit.Assert._
import org.junit.Test
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.camel.Message

class MessageTest extends JUnitSuite {

  @Test def shouldConvertDoubleBodyToString = {
    assertEquals("1.4", new Message(1.4, null).bodyAs(classOf[String]))
  }

  @Test def shouldThrowExceptionWhenConvertingDoubleBodyToInputStream {
    intercept[NoTypeConversionAvailableException] {
      new Message(1.4, null).bodyAs(classOf[InputStream])
    }
  }

}