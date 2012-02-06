package akka.camel.internal

import java.util.{ Map ⇒ JMap, Set ⇒ JSet }

import scala.collection.JavaConversions._

import org.apache.camel.util.ExchangeHelper

import akka.japi.{ Function ⇒ JFunction }
import org.apache.camel.{ Exchange, Message ⇒ CamelMessage }
import akka.camel.{Failure, Message}

/**
 *  Adapter for converting an org.apache.camel.Exchange to and from Message and Failure objects.
 *
 * @author Martin Krasser
 */
//TODO: rething/rewrite this
private[camel] class CamelExchangeAdapter(exchange: Exchange) {
  def getExchangeId = exchange.getExchangeId

  def isOutCapable = exchange.getPattern.isOutCapable

  /**
   * Sets Exchange.getIn from the given Message object.
   */
  def setRequest(msg: Message): Exchange = { msg.copyContentTo(exchange.getIn); exchange }

  /**
   * Depending on the exchange pattern, sets Exchange.getIn or Exchange.getOut from the given
   * Message object. If the exchange is out-capable then the Exchange.getOut is set, otherwise
   * Exchange.getIn.
   */
  def setResponse(msg: Message): Exchange = { msg.copyContentTo(response); exchange }

  /**
   * Sets Exchange.getException from the given Failure message. Headers of the Failure message
   * are ignored.
   */
  def setFailure(msg: Failure): Exchange = { exchange.setException(msg.cause); exchange }

  /**
   * Creates a Message object from Exchange.getIn.
   */
  def toRequestMessage: Message = toRequestMessage(Map.empty)

  /**
   * Depending on the exchange pattern, creates a Message object from Exchange.getIn or Exchange.getOut.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   */
  def toResponseMessage: Message = toResponseMessage(Map.empty)

  /**
   * Creates a Failure object from the adapted Exchange.
   *
   * @see Failure
   */
  def toFailureMessage: Failure = toFailureMessage(Map.empty)

  /**
   * Creates a Message object from Exchange.getIn.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   */
  def toRequestMessage(headers: Map[String, Any]): Message = Message.from(request, headers)

  /**
   * Depending on the exchange pattern, creates a Message object from Exchange.getIn or Exchange.getOut.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   */
  def toResponseMessage(headers: Map[String, Any]): Message = Message.from(response, headers)

  /**
   * Creates a Failure object from the adapted Exchange.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   *
   * @see Failure
   */
  def toFailureMessage(headers: Map[String, Any]): Failure =
    Failure(exchange.getException, headers ++ response.getHeaders)

  private def request = exchange.getIn

  private def response : CamelMessage = ExchangeHelper.getResultMessage(exchange)

}