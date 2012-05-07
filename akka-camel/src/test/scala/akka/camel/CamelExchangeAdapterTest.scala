/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.CamelExchangeAdapter
import org.apache.camel.impl.DefaultExchange
import org.apache.camel.{ Exchange, ExchangePattern }
import akka.camel.TestSupport.{ SharedCamelSystem, MessageSugar }
import org.scalatest.FunSuite

class CamelExchangeAdapterTest extends FunSuite with SharedCamelSystem with MessageSugar {

  //TODO: Get rid of implicit.
  // It is here, as was easier to add this implicit than to rewrite the whole test...
  implicit def exchangeToAdapter(e: Exchange) = new CamelExchangeAdapter(e)

  test("mustSetInMessageFromRequestMessage") {
    val e1 = sampleInOnly
    e1.setRequest(Message("x"))
    assert(e1.getIn.getBody === "x")
    val e2 = sampleInOut
    e2.setRequest(Message("y"))
    assert(e2.getIn.getBody === "y")
  }

  test("mustSetOutMessageFromResponseMessage") {
    val e1 = sampleInOut
    e1.setResponse(Message("y"))
    assert(e1.getOut.getBody === "y")
  }

  test("mustSetInMessageFromResponseMessage") {
    val e1 = sampleInOnly
    e1.setResponse(Message("x"))
    assert(e1.getIn.getBody === "x")
  }

  test("mustSetExceptionFromFailureMessage") {
    val e1 = sampleInOnly
    e1.setFailure(Failure(new Exception("test1")))
    assert(e1.getException.getMessage === "test1")
    val e2 = sampleInOut
    e2.setFailure(Failure(new Exception("test2")))
    assert(e2.getException.getMessage === "test2")
  }

  test("mustCreateRequestMessageFromInMessage") {
    val m = sampleInOnly.toRequestMessage
    assert(m === Message("test-in", Map("key-in" -> "val-in")))
  }

  test("mustCreateResponseMessageFromInMessage") {
    val m = sampleInOnly.toResponseMessage
    assert(m === Message("test-in", Map("key-in" -> "val-in")))
  }

  test("mustCreateResponseMessageFromOutMessage") {
    val m = sampleInOut.toResponseMessage
    assert(m === Message("test-out", Map("key-out" -> "val-out")))
  }

  test("mustCreateFailureMessageFromExceptionAndInMessage") {
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    assert(e1.toAkkaCamelException.getMessage === "test1")
    assert(e1.toAkkaCamelException.headers("key-in") === "val-in")
    assert(e1.toFailureMessage.cause.getMessage === "test1")
    assert(e1.toFailureMessage.headers("key-in") === "val-in")
  }

  test("mustCreateFailureMessageFromExceptionAndOutMessage") {
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    assert(e1.toAkkaCamelException.getMessage === "test2")
    assert(e1.toAkkaCamelException.headers("key-out") === "val-out")
    assert(e1.toFailureMessage.cause.getMessage === "test2")
    assert(e1.toFailureMessage.headers("key-out") === "val-out")
  }

  test("mustCreateRequestMessageFromInMessageWithAdditionalHeader") {
    val m = sampleInOnly.toRequestMessage(Map("x" -> "y"))
    assert(m === Message("test-in", Map("key-in" -> "val-in", "x" -> "y")))
  }

  test("mustCreateResponseMessageFromInMessageWithAdditionalHeader") {
    val m = sampleInOnly.toResponseMessage(Map("x" -> "y"))
    assert(m === Message("test-in", Map("key-in" -> "val-in", "x" -> "y")))
  }

  test("mustCreateResponseMessageFromOutMessageWithAdditionalHeader") {
    val m = sampleInOut.toResponseMessage(Map("x" -> "y"))
    assert(m === Message("test-out", Map("key-out" -> "val-out", "x" -> "y")))
  }

  test("mustCreateFailureMessageFromExceptionAndInMessageWithAdditionalHeader") {
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    assert(e1.toAkkaCamelException.getMessage === "test1")
    val headers = e1.toAkkaCamelException(Map("x" -> "y")).headers
    assert(headers("key-in") === "val-in")
    assert(headers("x") === "y")

    assert(e1.toFailureMessage.cause.getMessage === "test1")
    val failureHeaders = e1.toFailureMessage(Map("x" -> "y")).headers
    assert(failureHeaders("key-in") === "val-in")
    assert(failureHeaders("x") === "y")

  }

  test("mustCreateFailureMessageFromExceptionAndOutMessageWithAdditionalHeader") {
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    assert(e1.toAkkaCamelException.getMessage === "test2")
    val headers = e1.toAkkaCamelException(Map("x" -> "y")).headers
    assert(headers("key-out") === "val-out")
    assert(headers("x") === "y")
    assert(e1.toFailureMessage.cause.getMessage === "test2")
    val failureHeaders = e1.toFailureMessage(Map("x" -> "y")).headers
    assert(failureHeaders("key-out") === "val-out")
    assert(failureHeaders("x") === "y")
  }

  private def sampleInOnly = sampleExchange(ExchangePattern.InOnly)
  private def sampleInOut = sampleExchange(ExchangePattern.InOut)

  private def sampleExchange(pattern: ExchangePattern) = {
    val exchange = new DefaultExchange(camel.context)
    exchange.getIn.setBody("test-in")
    exchange.getOut.setBody("test-out")
    exchange.getIn.setHeader("key-in", "val-in")
    exchange.getOut.setHeader("key-out", "val-out")
    exchange.setPattern(pattern)
    exchange
  }
}
