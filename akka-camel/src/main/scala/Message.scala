/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import org.apache.camel.{Exchange, Message => CamelMessage}
import org.apache.camel.util.ExchangeHelper

/**
 * An immutable representation of a Camel message. Actor classes that mix in
 * se.scalablesolutions.akka.camel.Producer or
 * se.scalablesolutions.akka.camel.Consumer use this message type for communication.
 *
 * @author Martin Krasser
 */
case class Message(val body: Any, val headers: Map[String, Any] = Map.empty) {
  /**
   * Returns the body of the message converted to the type given by the <code>clazz</code>
   * argument. Conversion is done using Camel's type converter. The type converter is obtained
   * from the CamelContext managed by CamelContextManager. Applications have to ensure proper
   * initialization of CamelContextManager.
   *
   * @see CamelContextManager.
   */
  def bodyAs[T](clazz: Class[T]): T =
    CamelContextManager.context.getTypeConverter.mandatoryConvertTo[T](clazz, body)

  /**
   * Returns the body of the message converted to the type <code>T</code>. Conversion is done
   * using Camel's type converter. The type converter is obtained from the CamelContext managed
   * by CamelContextManager. Applications have to ensure proper initialization of
   * CamelContextManager.
   *
   * @see CamelContextManager.
   */
  def bodyAs[T](implicit m: Manifest[T]): T =
    CamelContextManager.context.getTypeConverter.mandatoryConvertTo[T](m.erasure.asInstanceOf[Class[T]], body)

  /**
   * Returns those headers from this message whose name is contained in <code>names</code>.
   */
  def headers(names: Set[String]): Map[String, Any] = headers.filter(names contains _._1)

  /**
   * Returns the header with given <code>name</code>. Throws <code>NoSuchElementException</code>
   * if the header doesn't exist.
   */
  def header(name: String): Any = headers(name)

  /**
   * Returns the header with given <code>name</code> converted to type <code>T</code>. Throws
   * <code>NoSuchElementException</code> if the header doesn't exist.
   */
  def headerAs[T](name: String)(implicit m: Manifest[T]): T =
    CamelContextManager.context.getTypeConverter.mandatoryConvertTo[T](m.erasure.asInstanceOf[Class[T]], header(name))

  /**
   * Returns the header with given <code>name</code> converted to type given by the <code>clazz</code>
   * argument. Throws <code>NoSuchElementException</code> if the header doesn't exist.
   */
  def headerAs[T](name: String, clazz: Class[T]): T =
    CamelContextManager.context.getTypeConverter.mandatoryConvertTo[T](clazz, header(name))

  /**
   * Creates a Message with a new <code>body</code> using a <code>transformer</code> function.
   */
  def transformBody[A](transformer: A => Any): Message = setBody(transformer(body.asInstanceOf[A]))

  /**
   * Creates a Message with a new <code>body</code> converted to type <code>clazz</code>.
   *
   * @see Message#bodyAs(Class)
   */
  @deprecated("use setBodyAs[T](implicit m: Manifest[T]): Message instead")
  def setBodyAs[T](clazz: Class[T]): Message = setBody(bodyAs(clazz))

  /**
   * Creates a Message with a new <code>body</code> converted to type <code>T</code>.
   *
   * @see Message#bodyAs(Class)
   */
  def setBodyAs[T](implicit m: Manifest[T]): Message = setBody(bodyAs[T])

  /**
   * Creates a Message with a new <code>body</code>.
   */
  def setBody(body: Any) = new Message(body, this.headers)

  /**
   * Creates a new Message with new <code>headers</code>.
   */
  def setHeaders(headers: Map[String, Any]) = copy(this.body, headers)

  /**
   * Creates a new Message with the <code>headers</code> argument added to the existing headers.
   */
  def addHeaders(headers: Map[String, Any]) = copy(this.body, this.headers ++ headers)

  /**
   * Creates a new Message with the <code>header</code> argument added to the existing headers.
   */
  def addHeader(header: (String, Any)) = copy(this.body, this.headers + header)

  /**
   * Creates a new Message where the header with name <code>headerName</code> is removed from
   * the existing headers.
   */
  def removeHeader(headerName: String) = copy(this.body, this.headers - headerName)
}

/**
 * Companion object of Message class.
 *
 * @author Martin Krasser
 */
object Message {

  /**
   * Message header to correlate request with response messages. Applications that send
   * messages to a Producer actor may want to set this header on the request message
   * so that it can be correlated with an asynchronous response. Messages send to Consumer
   * actors have this header already set.
   */
  val MessageExchangeId = "MessageExchangeId".intern

  /**
   * Creates a new Message with <code>body</code> as message body and an empty header map.
   */
  def apply(body: Any) = new Message(body)

  /**
   * Creates a canonical form of the given message <code>msg</code>. If <code>msg</code> of type
   * Message then <code>msg</code> is returned, otherwise <code>msg</code> is set as body of a
   * newly created Message object.
   */
  def canonicalize(msg: Any) = msg match {
    case mobj: Message => mobj
    case body          => new Message(body)
  }
}

/**
 * An immutable representation of a failed Camel exchange. It contains the failure cause
 * obtained from Exchange.getException and the headers from either the Exchange.getIn
 * message or Exchange.getOut message, depending on the exchange pattern.
 *
 * @author Martin Krasser
 */
case class Failure(val cause: Exception, val headers: Map[String, Any] = Map.empty)

/**
 * Adapter for converting an org.apache.camel.Exchange to and from Message and Failure objects.
 *
 *  @author Martin Krasser
 */
class CamelExchangeAdapter(exchange: Exchange) {

  import CamelMessageConversion.toMessageAdapter

  /**
   * Sets Exchange.getIn from the given Message object.
   */
  def fromRequestMessage(msg: Message): Exchange = { requestMessage.fromMessage(msg); exchange }

  /**
   * Depending on the exchange pattern, sets Exchange.getIn or Exchange.getOut from the given
   * Message object. If the exchange is out-capable then the Exchange.getOut is set, otherwise
   * Exchange.getIn.
   */
  def fromResponseMessage(msg: Message): Exchange = { responseMessage.fromMessage(msg); exchange }

  /**
   * Sets Exchange.getException from the given Failure message. Headers of the Failure message
   * are ignored.
   */
  def fromFailureMessage(msg: Failure): Exchange = { exchange.setException(msg.cause); exchange }

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
  def toRequestMessage(headers: Map[String, Any]): Message = requestMessage.toMessage(headers)

  /**
   * Depending on the exchange pattern, creates a Message object from Exchange.getIn or Exchange.getOut.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   */
  def toResponseMessage(headers: Map[String, Any]): Message = responseMessage.toMessage(headers)

  /**
   * Creates a Failure object from the adapted Exchange.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   *
   * @see Failure
   */
  def toFailureMessage(headers: Map[String, Any]): Failure =
    Failure(exchange.getException, headers ++ responseMessage.toMessage.headers)

  private def requestMessage = exchange.getIn

  private def responseMessage = ExchangeHelper.getResultMessage(exchange)

}

/**
 * Adapter for converting an org.apache.camel.Message to and from Message objects.
 *
 *  @author Martin Krasser
 */
class CamelMessageAdapter(val cm: CamelMessage) {
  /**
   * Set the adapted Camel message from the given Message object.
   */
  def fromMessage(m: Message): CamelMessage = {
    cm.setBody(m.body)
    for (h <- m.headers) cm.getHeaders.put(h._1, h._2.asInstanceOf[AnyRef])
    cm
  }

  /**
   * Creates a new Message object from the adapted Camel message.
   */
  def toMessage: Message = toMessage(Map.empty)

  /**
   * Creates a new Message object from the adapted Camel message.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   */
  def toMessage(headers: Map[String, Any]): Message = Message(cm.getBody, cmHeaders(headers, cm))

  import scala.collection.JavaConversions._

  private def cmHeaders(headers: Map[String, Any], cm: CamelMessage) =
    headers ++ cm.getHeaders
}

/**
 * Defines conversion methods to CamelExchangeAdapter and CamelMessageAdapter.
 * Imported by applications
 * that implicitly want to use conversion methods of CamelExchangeAdapter and CamelMessageAdapter.
 */
object CamelMessageConversion {

  /**
   * Creates an CamelExchangeAdapter for the given Camel exchange.
   */
  implicit def toExchangeAdapter(ce: Exchange): CamelExchangeAdapter =
    new CamelExchangeAdapter(ce)

  /**
   * Creates an CamelMessageAdapter for the given Camel message.
   */
  implicit def toMessageAdapter(cm: CamelMessage): CamelMessageAdapter =
    new CamelMessageAdapter(cm)
}
