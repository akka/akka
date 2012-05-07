package akka.camel.internal

import scala.collection.JavaConversions._

import org.apache.camel.util.ExchangeHelper

import org.apache.camel.{ Exchange, Message ⇒ JCamelMessage }
import akka.camel.{ Failure, AkkaCamelException, CamelMessage }

/**
 *  For internal use only.
 *  Adapter for converting an [[org.apache.camel.Exchange]] to and from [[akka.camel.CamelMessage]] and [[akka.camel.Failure]] objects.
 *
 * @author Martin Krasser
 */
private[camel] class CamelExchangeAdapter(exchange: Exchange) {
  /**
   * Returns the exchange id
   */
  def getExchangeId = exchange.getExchangeId

  /**
   * Returns if the exchange is out capable.
   */
  def isOutCapable = exchange.getPattern.isOutCapable

  /**
   * Sets Exchange.getIn from the given CamelMessage object.
   */
  def setRequest(msg: CamelMessage) { msg.copyContentTo(request) }

  /**
   * Depending on the exchange pattern, sets Exchange.getIn or Exchange.getOut from the given
   * CamelMessage object. If the exchange is out-capable then the Exchange.getOut is set, otherwise
   * Exchange.getIn.
   */
  def setResponse(msg: CamelMessage) { msg.copyContentTo(response) }

  /**
   * Sets Exchange.getException from the given Failure message. Headers of the Failure message
   * are ignored.
   */
  def setFailure(msg: Failure) { exchange.setException(msg.cause) }

  /**
   * Creates a CamelMessage object from Exchange.getIn.
   */
  def toRequestMessage: CamelMessage = toRequestMessage(Map.empty)

  /**
   * Depending on the exchange pattern, creates a CamelMessage object from Exchange.getIn or Exchange.getOut.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   */
  def toResponseMessage: CamelMessage = toResponseMessage(Map.empty)

  /**
   * Creates an AkkaCamelException object from the adapted Exchange.
   *
   * @see AkkaCamelException
   */
  def toAkkaCamelException: AkkaCamelException = toAkkaCamelException(Map.empty)

  /**
   * Creates an AkkaCamelException object from the adapted Exchange.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   *
   * @see AkkaCamelException
   */
  def toAkkaCamelException(headers: Map[String, Any]): AkkaCamelException =
    new AkkaCamelException(exchange.getException, headers ++ response.getHeaders)

  /**
   * Creates a Failure object from the adapted Exchange.
   *
   * @see Failure
   */
  def toFailureMessage: Failure = toFailureMessage(Map.empty)

  /**
   * Creates a Failure object from the adapted Exchange.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   *
   * @see Failure
   */
  def toFailureMessage(headers: Map[String, Any]): Failure = Failure(exchange.getException, headers ++ response.getHeaders)

  /**
   * Creates a CamelMessage object from Exchange.getIn.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  def toRequestMessage(headers: Map[String, Any]): CamelMessage = CamelMessage.from(request, headers)

  /**
   * Depending on the exchange pattern, creates a CamelMessage object from Exchange.getIn or Exchange.getOut.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  def toResponseMessage(headers: Map[String, Any]): CamelMessage = CamelMessage.from(response, headers)

  private def request = exchange.getIn

  private def response: JCamelMessage = ExchangeHelper.getResultMessage(exchange)

}