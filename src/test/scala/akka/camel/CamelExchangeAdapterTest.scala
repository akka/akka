package akka.camel

import org.apache.camel.impl.{DefaultCamelContext, DefaultExchange}
import org.apache.camel.ExchangePattern
import org.junit.Test
import org.scalatest.junit.JUnitSuite

class CamelExchangeAdapterTest extends JUnitSuite {
  import CamelMessageConversion.toExchangeAdapter

  @Test def shouldSetInMessageFromRequestMessage = {
    val e1 = sampleInOnly.fromRequestMessage(Message("x"))
    assert(e1.getIn.getBody === "x")
    val e2 = sampleInOut.fromRequestMessage(Message("y"))
    assert(e2.getIn.getBody === "y")
  }

  @Test def shouldSetOutMessageFromResponseMessage = {
    val e1 = sampleInOut.fromResponseMessage(Message("y"))
    assert(e1.getOut.getBody === "y")
  }

  @Test def shouldSetInMessageFromResponseMessage = {
    val e1 = sampleInOnly.fromResponseMessage(Message("x"))
    assert(e1.getIn.getBody === "x")
  }

  @Test def shouldSetExceptionFromFailureMessage = {
    val e1 = sampleInOnly.fromFailureMessage(Failure(new Exception("test1")))
    assert(e1.getException.getMessage === "test1")
    val e2 = sampleInOut.fromFailureMessage(Failure(new Exception("test2")))
    assert(e2.getException.getMessage === "test2")
  }

  @Test def shouldCreateRequestMessageFromInMessage = {
    val m = sampleInOnly.toRequestMessage
    assert(m === Message("test-in", Map("key-in" -> "val-in")))
  }

  @Test def shouldCreateResponseMessageFromInMessage = {
    val m = sampleInOnly.toResponseMessage
    assert(m === Message("test-in", Map("key-in" -> "val-in")))
  }

  @Test def shouldCreateResponseMessageFromOutMessage = {
    val m = sampleInOut.toResponseMessage
    assert(m === Message("test-out", Map("key-out" -> "val-out")))
  }

  @Test def shouldCreateFailureMessageFromExceptionAndInMessage = {
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    assert(e1.toFailureMessage.cause.getMessage === "test1")
    assert(e1.toFailureMessage.headers("key-in") === "val-in")
  }

  @Test def shouldCreateFailureMessageFromExceptionAndOutMessage = {
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    assert(e1.toFailureMessage.cause.getMessage === "test2")
    assert(e1.toFailureMessage.headers("key-out") === "val-out")
  }

  @Test def shouldCreateRequestMessageFromInMessageWithAdditionalHeader = {
    val m = sampleInOnly.toRequestMessage(Map("x" -> "y"))
    assert(m === Message("test-in", Map("key-in" -> "val-in", "x" -> "y")))
  }

  @Test def shouldCreateResponseMessageFromInMessageWithAdditionalHeader = {
    val m = sampleInOnly.toResponseMessage(Map("x" -> "y"))
    assert(m === Message("test-in", Map("key-in" -> "val-in", "x" -> "y")))
  }

  @Test def shouldCreateResponseMessageFromOutMessageWithAdditionalHeader = {
    val m = sampleInOut.toResponseMessage(Map("x" -> "y"))
    assert(m === Message("test-out", Map("key-out" -> "val-out", "x" -> "y")))
  }

  @Test def shouldCreateFailureMessageFromExceptionAndInMessageWithAdditionalHeader = {
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    assert(e1.toFailureMessage.cause.getMessage === "test1")
    val headers = e1.toFailureMessage(Map("x" -> "y")).headers
    assert(headers("key-in") === "val-in")
    assert(headers("x") === "y")
  }

  @Test def shouldCreateFailureMessageFromExceptionAndOutMessageWithAdditionalHeader = {
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    assert(e1.toFailureMessage.cause.getMessage === "test2")
    val headers = e1.toFailureMessage(Map("x" -> "y")).headers
    assert(headers("key-out") === "val-out")
    assert(headers("x") === "y")
  }

  private def sampleInOnly = sampleExchange(ExchangePattern.InOnly)
  private def sampleInOut = sampleExchange(ExchangePattern.InOut)

  private def sampleExchange(pattern: ExchangePattern) = {
    val exchange = new DefaultExchange(new DefaultCamelContext)
    exchange.getIn.setBody("test-in")
    exchange.getOut.setBody("test-out")
    exchange.getIn.setHeader("key-in", "val-in")
    exchange.getOut.setHeader("key-out", "val-out")
    exchange.setPattern(pattern)
    exchange
  }
}
