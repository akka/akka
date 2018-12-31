/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel

import akka.camel.TestSupport.SharedCamelSystem
import akka.camel.internal.CamelExchangeAdapter
import org.apache.camel.impl.DefaultExchange
import org.apache.camel.{ Exchange, ExchangePattern }
import org.scalatest.FunSuite

class CamelExchangeAdapterTest extends FunSuite with SharedCamelSystem {

  test("mustSetInMessageFromRequestMessage") {
    val e1 = sampleInOnly
    exchangeToAdapter(e1).setRequest(CamelMessage("x", Map.empty))
    assert(e1.getIn.getBody === "x")
    val e2 = sampleInOut
    exchangeToAdapter(e2).setRequest(CamelMessage("y", Map.empty))
    assert(e2.getIn.getBody === "y")
  }

  test("mustSetOutMessageFromResponseMessage") {
    val e1 = sampleInOut
    exchangeToAdapter(e1).setResponse(CamelMessage("y", Map.empty))
    assert(e1.getOut.getBody === "y")
  }

  test("mustSetInMessageFromResponseMessage") {
    val e1 = sampleInOnly
    exchangeToAdapter(e1).setResponse(CamelMessage("x", Map.empty))
    assert(e1.getIn.getBody === "x")
  }

  test("mustSetExceptionFromFailureMessage") {
    val e1 = sampleInOnly
    exchangeToAdapter(e1).setFailure(FailureResult(new Exception("test1")))
    assert(e1.getException.getMessage === "test1")
    val e2 = sampleInOut
    exchangeToAdapter(e2).setFailure(FailureResult(new Exception("test2")))
    assert(e2.getException.getMessage === "test2")
  }

  test("mustCreateRequestMessageFromInMessage") {
    val m = exchangeToAdapter(sampleInOnly).toRequestMessage
    assert(m === CamelMessage("test-in", Map("key-in" → "val-in")))
  }

  test("mustCreateResponseMessageFromInMessage") {
    val m = exchangeToAdapter(sampleInOnly).toResponseMessage
    assert(m === CamelMessage("test-in", Map("key-in" → "val-in")))
  }

  test("mustCreateResponseMessageFromOutMessage") {
    val m = exchangeToAdapter(sampleInOut).toResponseMessage
    assert(m === CamelMessage("test-out", Map("key-out" → "val-out")))
  }

  test("mustCreateFailureMessageFromExceptionAndInMessage") {
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    val a1 = exchangeToAdapter(e1)
    assert(a1.toAkkaCamelException.getMessage === "test1")
    assert(a1.toAkkaCamelException.headers("key-in") === "val-in")
    assert(a1.toFailureMessage.cause.getMessage === "test1")
    assert(a1.toFailureMessage.headers("key-in") === "val-in")
  }

  test("mustCreateFailureMessageFromExceptionAndOutMessage") {
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    val a1 = exchangeToAdapter(e1)
    assert(a1.toAkkaCamelException.getMessage === "test2")
    assert(a1.toAkkaCamelException.headers("key-out") === "val-out")
    assert(a1.toFailureMessage.cause.getMessage === "test2")
    assert(a1.toFailureMessage.headers("key-out") === "val-out")
  }

  test("mustCreateRequestMessageFromInMessageWithAdditionalHeader") {
    val m = exchangeToAdapter(sampleInOnly).toRequestMessage(Map("x" → "y"))
    assert(m === CamelMessage("test-in", Map("key-in" → "val-in", "x" → "y")))
  }

  test("mustCreateResponseMessageFromInMessageWithAdditionalHeader") {
    val m = exchangeToAdapter(sampleInOnly).toResponseMessage(Map("x" → "y"))
    assert(m === CamelMessage("test-in", Map("key-in" → "val-in", "x" → "y")))
  }

  test("mustCreateResponseMessageFromOutMessageWithAdditionalHeader") {
    val m = exchangeToAdapter(sampleInOut).toResponseMessage(Map("x" → "y"))
    assert(m === CamelMessage("test-out", Map("key-out" → "val-out", "x" → "y")))
  }

  test("mustCreateFailureMessageFromExceptionAndInMessageWithAdditionalHeader") {
    val e1 = sampleInOnly
    e1.setException(new Exception("test1"))
    val a1 = exchangeToAdapter(e1)
    assert(a1.toAkkaCamelException.getMessage === "test1")
    val headers = a1.toAkkaCamelException(Map("x" → "y")).headers
    assert(headers("key-in") === "val-in")
    assert(headers("x") === "y")

    val a2 = exchangeToAdapter(e1)
    assert(a2.toFailureMessage.cause.getMessage === "test1")
    val failureHeaders = a2.toFailureResult(Map("x" → "y")).headers
    assert(failureHeaders("key-in") === "val-in")
    assert(failureHeaders("x") === "y")

  }

  test("mustCreateFailureMessageFromExceptionAndOutMessageWithAdditionalHeader") {
    val e1 = sampleInOut
    e1.setException(new Exception("test2"))
    val a1 = exchangeToAdapter(e1)
    assert(a1.toAkkaCamelException.getMessage === "test2")
    val headers = a1.toAkkaCamelException(Map("x" → "y")).headers
    assert(headers("key-out") === "val-out")
    assert(headers("x") === "y")

    val a2 = exchangeToAdapter(e1)
    assert(a2.toFailureMessage.cause.getMessage === "test2")
    val failureHeaders = a2.toFailureResult(Map("x" → "y")).headers
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

  private def exchangeToAdapter(e: Exchange) = new CamelExchangeAdapter(e)
}
