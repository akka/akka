package akka.camel

import org.apache.camel.impl.DefaultExchange
import org.apache.camel.{Exchange, ExchangePattern}
import akka.camel.TestSupport.{SharedCamelSystem, MessageSugar}
import org.scalatest.FunSuite


class CamelExchangeAdapterTest extends FunSuite with SharedCamelSystem with MessageSugar{

  //TODO: Get rid of implicit.
  // It is here, as was easier to add this implicit than to rewrite the whole test...
  implicit def exchangeToAdapter(e:Exchange) = new CamelExchangeAdapter(e)

  test("shouldSetInMessageFromRequestMessage"){
    val e1 = sampleInOnly.setRequest(Message("x"))
    assert(e1.getIn.getBody === "x")
    val e2 = sampleInOut.setRequest(Message("y"))
    assert(e2.getIn.getBody === "y")
  }

  test("shouldSetOutMessageFromResponseMessage"){
    val e1 = sampleInOut.setResponse(Message("y"))
    assert(e1.getOut.getBody === "y")
  }

  test("shouldSetInMessageFromResponseMessage"){
    val e1 = sampleInOnly.setResponse(Message("x"))
    assert(e1.getIn.getBody === "x")
  }

  test("shouldSetExceptionFromFailureMessage"){
    val e1 = sampleInOnly.setFailure(Failure(new Exception("test1")))
    assert(e1.getException.getMessage === "test1")
    val e2 = sampleInOut.setFailure(Failure(new Exception("test2")))
    assert(e2.getException.getMessage === "test2")
  }

  test("shouldCreateRequestMessageFromInMessage"){
    val m = sampleInOnly.toRequestMessage
    assert(m === Message("test-in", Map("key-in" -> "val-in")))
  }

  test("shouldCreateResponseMessageFromInMessage"){
    val m = sampleInOnly.toResponseMessage
    assert(m === Message("test-in", Map("key-in" -> "val-in")))
  }

  test("shouldCreateResponseMessageFromOutMessage"){
    val m = sampleInOut.toResponseMessage
    assert(m === Message("test-out", Map("key-out" -> "val-out")))
  }

  test("shouldCreateFailureMessageFromExceptionAndInMessage"){
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    assert(e1.toFailureMessage.cause.getMessage === "test1")
    assert(e1.toFailureMessage.headers("key-in") === "val-in")
  }

  test("shouldCreateFailureMessageFromExceptionAndOutMessage"){
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    assert(e1.toFailureMessage.cause.getMessage === "test2")
    assert(e1.toFailureMessage.headers("key-out") === "val-out")
  }

  test("shouldCreateRequestMessageFromInMessageWithAdditionalHeader"){
    val m = sampleInOnly.toRequestMessage(Map("x" -> "y"))
    assert(m === Message("test-in", Map("key-in" -> "val-in", "x" -> "y")))
  }

  test("shouldCreateResponseMessageFromInMessageWithAdditionalHeader"){
    val m = sampleInOnly.toResponseMessage(Map("x" -> "y"))
    assert(m === Message("test-in", Map("key-in" -> "val-in", "x" -> "y")))
  }

  test("shouldCreateResponseMessageFromOutMessageWithAdditionalHeader"){
    val m = sampleInOut.toResponseMessage(Map("x" -> "y"))
    assert(m === Message("test-out", Map("key-out" -> "val-out", "x" -> "y")))
  }

  test("shouldCreateFailureMessageFromExceptionAndInMessageWithAdditionalHeader"){
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    assert(e1.toFailureMessage.cause.getMessage === "test1")
    val headers = e1.toFailureMessage(Map("x" -> "y")).headers
    assert(headers("key-in") === "val-in")
    assert(headers("x") === "y")
  }

  test("shouldCreateFailureMessageFromExceptionAndOutMessageWithAdditionalHeader"){
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    assert(e1.toFailureMessage.cause.getMessage === "test2")
    val headers = e1.toFailureMessage(Map("x" -> "y")).headers
    assert(headers("key-out") === "val-out")
    assert(headers("x") === "y")
  }

  private def sampleInOnly = sampleExchange(ExchangePattern.InOnly)
  private def sampleInOut = sampleExchange(ExchangePattern.InOut)

  private def sampleExchange(pattern: ExchangePattern)={
    val exchange = new DefaultExchange(camel.context)
    exchange.getIn.setBody("test-in")
    exchange.getOut.setBody("test-out")
    exchange.getIn.setHeader("key-in", "val-in")
    exchange.getOut.setHeader("key-out", "val-out")
    exchange.setPattern(pattern)
    exchange
  }
}
