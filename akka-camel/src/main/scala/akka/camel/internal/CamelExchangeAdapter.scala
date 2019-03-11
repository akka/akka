/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.internal

import org.apache.camel.util.ExchangeHelper
import org.apache.camel.{ Exchange, Message => JCamelMessage }
import akka.camel.{ AkkaCamelException, CamelMessage, FailureResult }

/**
 * INTERNAL API
 * Adapter for converting an [[org.apache.camel.Exchange]] to and from [[akka.camel.CamelMessage]] and [[akka.camel.FailureResult]] objects.
 * The org.apache.camel.Message is mutable and not suitable to be used directly as messages between Actors.
 * This adapter is used to convert to immutable messages to be used with Actors, and convert the immutable messages back
 * to org.apache.camel.Message when using Camel.
 */
private[camel] class CamelExchangeAdapter(val exchange: Exchange) {

  /**
   * Returns the exchange id
   */
  def getExchangeId: String = exchange.getExchangeId

  /**
   * Returns if the exchange is out capable.
   */
  def isOutCapable: Boolean = exchange.getPattern.isOutCapable

  /**
   * Sets Exchange.getIn from the given CamelMessage object.
   */
  def setRequest(msg: CamelMessage): Unit = CamelMessage.copyContent(msg, request)

  /**
   * Depending on the exchange pattern, sets Exchange.getIn or Exchange.getOut from the given
   * CamelMessage object. If the exchange is out-capable then the Exchange.getOut is set, otherwise
   * Exchange.getIn.
   */
  def setResponse(msg: CamelMessage): Unit = CamelMessage.copyContent(msg, response)

  /**
   * Sets Exchange.getException from the given FailureResult message. Headers of the FailureResult message
   * are ignored.
   */
  def setFailure(msg: FailureResult): Unit = exchange.setException(msg.cause)

  /**
   * Creates an immutable CamelMessage object from Exchange.getIn so it can be used with Actors.
   */
  def toRequestMessage: CamelMessage = toRequestMessage(Map.empty)

  /**
   * Depending on the exchange pattern, creates an immutable CamelMessage object from Exchange.getIn or Exchange.getOut so it can be used with Actors.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   */
  def toResponseMessage: CamelMessage = toResponseMessage(Map.empty)

  /**
   * Creates an AkkaCamelException object from the adapted Exchange.
   * The cause of the AkkaCamelException is set to the exception on the adapted Exchange.
   *
   * Depending on the exchange pattern, puts the headers from Exchange.getIn or Exchange.getOut
   * on the AkkaCamelException.
   *
   * If the exchange is out-capable then the headers of Exchange.getOut are used, otherwise the headers of Exchange.getIn are used.
   */
  def toAkkaCamelException: AkkaCamelException = toAkkaCamelException(Map.empty)

  /**
   * Creates an AkkaCamelException object from the adapted Exchange.
   * The cause of the AkkaCamelException is set to the exception on the adapted Exchange.
   *
   * Depending on the exchange pattern, adds the supplied headers and the headers from Exchange.getIn or Exchange.getOut
   * together and passes these to the AkkaCamelException.
   *
   * If the exchange is out-capable then the headers of Exchange.getOut are used, otherwise the headers of Exchange.getIn are used.
   *
   * @param headers additional headers to set on the exception in addition to those
   *                in the exchange.
   */
  def toAkkaCamelException(headers: Map[String, Any]): AkkaCamelException = {
    import scala.collection.JavaConverters._
    new AkkaCamelException(exchange.getException, headers ++ response.getHeaders.asScala)
  }

  /**
   * Creates an immutable Failure object from the adapted Exchange so it can be used internally between Actors.
   */
  def toFailureMessage: FailureResult = toFailureResult(Map.empty)

  /**
   * Creates an immutable FailureResult object from the adapted Exchange so it can be used internally between Actors.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  def toFailureResult(headers: Map[String, Any]): FailureResult = {
    import scala.collection.JavaConverters._
    FailureResult(exchange.getException, headers ++ response.getHeaders.asScala)
  }

  /**
   * Creates an immutable CamelMessage object from Exchange.getIn so it can be used with Actors.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  def toRequestMessage(headers: Map[String, Any]): CamelMessage = CamelMessage.from(request, headers)

  /**
   * Depending on the exchange pattern, creates an immutable CamelMessage object from Exchange.getIn or Exchange.getOut so it can be used with Actors.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  def toResponseMessage(headers: Map[String, Any]): CamelMessage = CamelMessage.from(response, headers)

  private def request: JCamelMessage = exchange.getIn

  private def response: JCamelMessage = ExchangeHelper.getResultMessage(exchange)

}
