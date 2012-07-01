package akka.camel.internal

import scala.collection.JavaConversions._

import org.apache.camel.util.ExchangeHelper

import org.apache.camel.{ Exchange, Message ⇒ JCamelMessage }
import akka.camel.{ FailureResult, AkkaCamelException, CamelMessage }

/**
 *  For internal use only.
 *  Adapter for converting an [[org.apache.camel.Exchange]] to and from [[akka.camel.CamelMessage]] and [[akka.camel.Failure]] objects.
 *  The org.apache.camel.Message is mutable and not suitable to be used directly as messages between Actors.
 *  This adapter is used to convert to immutable messages to be used with Actors, and convert the immutable messages back
 *  to org.apache.camel.Message when using Camel.
 *
 * @author Martin Krasser
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
  def setRequest(msg: CamelMessage): Unit = msg.copyContentTo(request)

  /**
   * Depending on the exchange pattern, sets Exchange.getIn or Exchange.getOut from the given
   * CamelMessage object. If the exchange is out-capable then the Exchange.getOut is set, otherwise
   * Exchange.getIn.
   */
  def setResponse(msg: CamelMessage): Unit = msg.copyContentTo(response)

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
   *
   * @see AkkaCamelException
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
   *
   * @see AkkaCamelException
   */
  def toAkkaCamelException(headers: Map[String, Any]): AkkaCamelException =
    new AkkaCamelException(exchange.getException, headers ++ response.getHeaders)

  /**
   * Creates an immutable Failure object from the adapted Exchange so it can be used internally between Actors.
   *
   * @see Failure
   */
  def toFailureMessage: FailureResult = toFailureResult(Map.empty)

  /**
   * Creates an immutable FailureResult object from the adapted Exchange so it can be used internally between Actors.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   *
   * @see Failure
   */
  def toFailureResult(headers: Map[String, Any]): FailureResult = FailureResult(exchange.getException, headers ++ response.getHeaders)

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