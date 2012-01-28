/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import java.util.{ Map ⇒ JMap, Set ⇒ JSet }

import scala.collection.JavaConversions._

import org.apache.camel.util.ExchangeHelper

import akka.japi.{ Function ⇒ JFunction }
import org.apache.camel.{ CamelContext, Exchange, Message ⇒ CamelMessage }

/**
 * An immutable representation of a Camel message.
 *
 * @author Martin Krasser
 */
case class Message(body: Any, headers: Map[String, Any]) {

  def this(body: Any, headers: JMap[String, Any]) = this(body, headers.toMap) //for Java

  override def toString = "Message(%s, %s)" format (body, headers)

  /**
   * Returns those headers from this message whose name is contained in <code>names</code>.
   */
  def headers(names: Set[String]): Map[String, Any] = headers.filterKeys(names contains _)

  /**
   * Returns those headers from this message whose name is contained in <code>names</code>.
   * The returned headers map is backed up by an immutable headers map. Any attempt to modify
   * the returned map will throw an exception.
   * <p>
   * Java API
   */
  def getHeaders(names: JSet[String]): JMap[String, Any] = headers(names.toSet)

  /**
   * Returns all headers from this message. The returned headers map is backed up by this
   * message's immutable headers map. Any attempt to modify the returned map will throw an
   * exception.
   * <p>
   * Java API
   */
  def getHeaders: JMap[String, Any] = headers

  /**
   * Returns the header with given <code>name</code>. Throws <code>NoSuchElementException</code>
   * if the header doesn't exist.
   */
  def header(name: String): Option[Any] = headers.get(name)

  /**
   * Returns the header with given <code>name</code>. Throws <code>NoSuchElementException</code>
   * if the header doesn't exist.
   * <p>
   * Java API
   */
  def getHeader(name: String): Any = headers(name)

  /**
   * Creates a Message with a transformed body using a <code>transformer</code> function.
   */
  def mapBody[A, B](transformer: A ⇒ B): Message = withBody(transformer(body.asInstanceOf[A]))

  /**
   * Creates a Message with a transformed body using a <code>transformer</code> function.
   * <p>
   * Java API
   */
  def mapBody[A, B](transformer: JFunction[A, B]): Message = withBody(transformer(body.asInstanceOf[A]))

  /**
   * Creates a Message with a given <code>body</code>.
   */
  def withBody(body: Any) = Message(body, this.headers)

  /**
   * Creates a new Message with given <code>headers</code>.
   */
  def withHeaders[A](headers: Map[String, A]): Message = copy(this.body, headers)

  /**
   * Creates a new Message with given <code>headers</code>. A copy of the headers map is made.
   * <p>
   * Java API
   */
  def withHeaders[A](headers: JMap[String, A]): Message = withHeaders(headers.toMap)

  /**
   * Creates a new Message with given <code>headers</code> added to the current headers.
   */
  def plusHeaders[A](headers: Map[String, A]): Message = copy(this.body, this.headers ++ headers)

  /**
   * Creates a new Message with given <code>headers</code> added to the current headers.
   * A copy of the headers map is made.
   * <p>
   * Java API
   */
  def plusHeaders[A](headers: JMap[String, A]): Message = plusHeaders(headers.toMap)

  /**
   * Creates a new Message with the given <code>header</code> added to the current headers.
   */
  def plusHeader(header: (String, Any)): Message = copy(this.body, this.headers + header)

  /**
   * Creates a new Message with the given header, represented by <code>name</code> and
   * <code>value</code> added to the existing headers.
   * <p>
   * Java API
   */
  def plusHeader(name: String, value: Any): Message = plusHeader((name, value))

  /**
   * Creates a new Message where the header with given <code>headerName</code> is removed from
   * the existing headers.
   */
  def withoutHeader(headerName: String) = copy(this.body, this.headers - headerName)
}

class RichMessage(message: Message, camelContext: CamelContext) {
  /**
   * Returns the body of the message converted to the type <code>T</code>. Conversion is done
   * using Camel's type converter. The type converter is obtained from the CamelContext managed
   * by CamelContextManager. Applications have to ensure proper initialization of
   * CamelContextManager.
   *
   * @see CamelContextManager.
   */

  def bodyAs[T](implicit m: Manifest[T]): T = getBodyAs(m.erasure.asInstanceOf[Class[T]])

  /**
   * Returns the body of the message converted to the type as given by the <code>clazz</code>
   * parameter. Conversion is done using Camel's type converter. The type converter is obtained
   * from the CamelContext managed by CamelContextManager. Applications have to ensure proper
   * initialization of CamelContextManager.
   * <p>
   * Java API
   *
   * @see CamelContextManager.
   */
  def getBodyAs[T](clazz: Class[T]): T =
    camelContext.getTypeConverter.mandatoryConvertTo[T](clazz, message.body)

  /**
   * Creates a Message with current <code>body</code> converted to type <code>T</code>.
   */
  def withBodyAs[T](implicit m: Manifest[T]): Message = withBodyAs(m.erasure.asInstanceOf[Class[T]])

  /**
   * Creates a Message with current <code>body</code> converted to type <code>clazz</code>.
   * <p>
   * Java API
   */
  def withBodyAs[T](clazz: Class[T]): Message = message.withBody(getBodyAs(clazz))

  /**
   * Returns the header with given <code>name</code> converted to type <code>T</code>. Throws
   * <code>NoSuchElementException</code> if the header doesn't exist.
   */
  def headerAs[T](name: String)(implicit m: Manifest[T]): Option[T] = message.header(name).map(camelContext.getTypeConverter.mandatoryConvertTo[T](m.erasure.asInstanceOf[Class[T]], _))

  /**
   * Returns the header with given <code>name</code> converted to type as given by the <code>clazz</code>
   * parameter. Throws <code>NoSuchElementException</code> if the header doesn't exist.
   * <p>
   * Java API
   */
  def getHeaderAs[T](name: String, clazz: Class[T]) = headerAs[T](name)(Manifest.classType(clazz)).get

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
   * Creates a canonical form of the given message <code>msg</code>. If <code>msg</code> of type
   * Message then <code>msg</code> is returned, otherwise <code>msg</code> is set as body of a
   * newly created Message object.
   */
  def canonicalize(msg: Any) = msg match {
    case mobj: Message ⇒ mobj
    case body          ⇒ Message(body, Map.empty)
  }
}

/**
 * Positive acknowledgement message (used for application-acknowledged message receipts).
 *
 * @author Martin Krasser
 */
case object Ack {
  /** Java API to get the Ack singleton */
  def ack = this
}

/**
 * An immutable representation of a failed Camel exchange. It contains the failure cause
 * obtained from Exchange.getException and the headers from either the Exchange.getIn
 * message or Exchange.getOut message, depending on the exchange pattern.
 *
 * @author Martin Krasser
 */
case class Failure(val cause: Throwable, val headers: Map[String, Any] = Map.empty) {

  /**
   * Creates a Failure with cause body and empty headers map.
   */
  def this(cause: Throwable) = this(cause, Map.empty[String, Any])

  /**
   * Creates a Failure with given cause and headers map. A copy of the headers map is made.
   * <p>
   * Java API
   */
  def this(cause: Throwable, headers: JMap[String, Any]) = this(cause, headers.toMap)

  /**
   * Returns the cause of this Failure.
   * <p>
   * Java API.
   */
  def getCause = cause

  /**
   * Returns all headers from this failure message. The returned headers map is backed up by
   * this message's immutable headers map. Any attempt to modify the returned map will throw
   * an exception.
   * <p>
   * Java API
   */
  def getHeaders: JMap[String, Any] = headers
}

object Failure {
  //  def fromMessage(m:Message) : Failure = {
  //    m.to
  //  }
}

/**
 *  Adapter for converting an org.apache.camel.Exchange to and from Message and Failure objects.
 *
 * @author Martin Krasser
 */
//TODO: think on improving method names
class CamelExchangeAdapter(exchange: Exchange) {
  def getExchangeId = exchange.getExchangeId

  def isOutCapable = exchange.getPattern.isOutCapable

  import CamelMessageConversion.toMessageAdapter

  /**
   * Sets Exchange.getIn from the given Message object.
   */
  def setRequest(msg: Message): Exchange = { request.copyContentFrom(msg); exchange }

  /**
   * Depending on the exchange pattern, sets Exchange.getIn or Exchange.getOut from the given
   * Message object. If the exchange is out-capable then the Exchange.getOut is set, otherwise
   * Exchange.getIn.
   */
  def setResponse(msg: Message): Exchange = { response.copyContentFrom(msg); exchange }

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
  def toRequestMessage(headers: Map[String, Any]): Message = request.toMessage(headers)

  /**
   * Depending on the exchange pattern, creates a Message object from Exchange.getIn or Exchange.getOut.
   * If the exchange is out-capable then the Exchange.getOut is set, otherwise Exchange.getIn.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   */
  def toResponseMessage(headers: Map[String, Any]): Message = response.toMessage(headers)

  /**
   * Creates a Failure object from the adapted Exchange.
   *
   * @param headers additional headers to set on the created Message in addition to those
   *                in the Camel message.
   *
   * @see Failure
   */
  def toFailureMessage(headers: Map[String, Any]): Failure =
    Failure(exchange.getException, headers ++ response.toMessage.headers)

  private def request = exchange.getIn

  private def response = ExchangeHelper.getResultMessage(exchange)

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
  def copyContentFrom(m: Message): CamelMessage = {
    cm.setBody(m.body)
    for (h ← m.headers) cm.getHeaders.put(h._1, h._2.asInstanceOf[AnyRef])
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

  private def cmHeaders(headers: Map[String, Any], cm: CamelMessage) = headers ++ cm.getHeaders
}

/**
 * Defines conversion methods to CamelExchangeAdapter and CamelMessageAdapter.
 * Imported by applications that implicitly want to use conversion methods of
 * CamelExchangeAdapter and CamelMessageAdapter.
 */
object CamelMessageConversion {

  /**
   * Creates a CamelExchangeAdaptor for the given Camel exchange
   */
  implicit def toExchangeAdapter(exchange: Exchange): CamelExchangeAdapter =
    new CamelExchangeAdapter(exchange)

  /**
   * Creates a CamelMessageAdapter for the given Camel message.
   */
  implicit def toMessageAdapter(cm: CamelMessage): CamelMessageAdapter =
    new CamelMessageAdapter(cm)
}
