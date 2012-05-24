/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import java.util.{ Map ⇒ JMap, Set ⇒ JSet }

import scala.collection.JavaConversions._

import akka.japi.{ Function ⇒ JFunction }
import org.apache.camel.{ CamelContext, Message ⇒ JCamelMessage }
import akka.AkkaException

/**
 * An immutable representation of a Camel message.
 *
 * @author Martin Krasser
 */
case class CamelMessage(body: Any, headers: Map[String, Any]) {

  def this(body: Any, headers: JMap[String, Any]) = this(body, headers.toMap) //for Java

  override def toString: String = "CamelMessage(%s, %s)" format (body, headers)

  /**
   * Returns those headers from this message whose name is contained in <code>names</code>.
   */
  def headers(names: Set[String]): Map[String, Any] = headers filterKeys names

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
   * Creates a CamelMessage with a transformed body using a <code>transformer</code> function.
   */
  def mapBody[A, B](transformer: A ⇒ B): CamelMessage = withBody(transformer(body.asInstanceOf[A]))

  /**
   * Creates a CamelMessage with a transformed body using a <code>transformer</code> function.
   * <p>
   * Java API
   */
  def mapBody[A, B](transformer: JFunction[A, B]): CamelMessage = withBody(transformer(body.asInstanceOf[A]))

  /**
   * Creates a CamelMessage with a given <code>body</code>.
   */
  def withBody(body: Any): CamelMessage = CamelMessage(body, this.headers)

  /**
   * Creates a new CamelMessage with given <code>headers</code>.
   */
  def withHeaders[A](headers: Map[String, A]): CamelMessage = copy(this.body, headers)

  /**
   * Creates a new CamelMessage with given <code>headers</code>. A copy of the headers map is made.
   * <p>
   * Java API
   */
  def withHeaders[A](headers: JMap[String, A]): CamelMessage = withHeaders(headers.toMap)

  /**
   * Creates a new CamelMessage with given <code>headers</code> added to the current headers.
   */
  def addHeaders[A](headers: Map[String, A]): CamelMessage = copy(this.body, this.headers ++ headers)

  /**
   * Creates a new CamelMessage with given <code>headers</code> added to the current headers.
   * A copy of the headers map is made.
   * <p>
   * Java API
   */
  def addHeaders[A](headers: JMap[String, A]): CamelMessage = addHeaders(headers.toMap)

  /**
   * Creates a new CamelMessage with the given <code>header</code> added to the current headers.
   */
  def addHeader(header: (String, Any)): CamelMessage = copy(this.body, this.headers + header)

  /**
   * Creates a new CamelMessage with the given header, represented by <code>name</code> and
   * <code>value</code> added to the existing headers.
   * <p>
   * Java API
   */
  def addHeader(name: String, value: Any): CamelMessage = addHeader((name, value))

  /**
   * Creates a new CamelMessage where the header with given <code>headerName</code> is removed from
   * the existing headers.
   */
  def withoutHeader(headerName: String): CamelMessage = copy(this.body, this.headers - headerName)

  def copyContentTo(to: JCamelMessage): Unit = {
    to.setBody(this.body)
    for ((name, value) ← this.headers) to.getHeaders.put(name, value.asInstanceOf[AnyRef])
  }

  /**
   * Returns the body of the message converted to the type <code>T</code>. Conversion is done
   * using Camel's type converter. The type converter is obtained from the CamelContext that is passed in.
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def bodyAs[T](implicit m: Manifest[T], camelContext: CamelContext): T = getBodyAs(m.erasure.asInstanceOf[Class[T]], camelContext)

  /**
   * Returns the body of the message converted to the type as given by the <code>clazz</code>
   * parameter. Conversion is done using Camel's type converter. The type converter is obtained
   * from the CamelContext that is passed in.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   * <p>
   * Java API
   *
   */
  def getBodyAs[T](clazz: Class[T], camelContext: CamelContext): T = camelContext.getTypeConverter.mandatoryConvertTo[T](clazz, body)

  /**
   * Creates a CamelMessage with current <code>body</code> converted to type <code>T</code>.
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def withBodyAs[T](implicit m: Manifest[T], camelContext: CamelContext): CamelMessage = withBodyAs(m.erasure.asInstanceOf[Class[T]])

  /**
   * Creates a CamelMessage with current <code>body</code> converted to type <code>clazz</code>.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   * <p>
   * Java API
   */
  def withBodyAs[T](clazz: Class[T])(implicit camelContext: CamelContext): CamelMessage = withBody(getBodyAs(clazz, camelContext))

  /**
   * Returns the header with given <code>name</code> converted to type <code>T</code>. Throws
   * <code>NoSuchElementException</code> if the header doesn't exist.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   *
   */
  def headerAs[T](name: String)(implicit m: Manifest[T], camelContext: CamelContext): Option[T] = header(name).map(camelContext.getTypeConverter.mandatoryConvertTo[T](m.erasure.asInstanceOf[Class[T]], _))

  /**
   * Returns the header with given <code>name</code> converted to type as given by the <code>clazz</code>
   * parameter. Throws <code>NoSuchElementException</code> if the header doesn't exist.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   * <p>
   * Java API
   */
  def getHeaderAs[T](name: String, clazz: Class[T], camelContext: CamelContext): T = headerAs[T](name)(Manifest.classType(clazz), camelContext).get

}

/**
 * Companion object of CamelMessage class.
 *
 * @author Martin Krasser
 */
object CamelMessage {

  /**
   * CamelMessage header to correlate request with response messages. Applications that send
   * messages to a Producer actor may want to set this header on the request message
   * so that it can be correlated with an asynchronous response. Messages send to Consumer
   * actors have this header already set.
   */
  val MessageExchangeId = "MessageExchangeId" //Deliberately without type ascription to make it a constant

  /**
   * Creates a canonical form of the given message <code>msg</code>. If <code>msg</code> of type
   * CamelMessage then <code>msg</code> is returned, otherwise <code>msg</code> is set as body of a
   * newly created CamelMessage object.
   */
  def canonicalize(msg: Any) = msg match {
    case mobj: CamelMessage ⇒ mobj
    case body               ⇒ CamelMessage(body, Map.empty)
  }

  /**
   * Creates a new CamelMessage object from the Camel message.
   */
  def from(camelMessage: JCamelMessage): CamelMessage = from(camelMessage, Map.empty)

  /**
   * Creates a new CamelMessage object from the Camel message.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  def from(camelMessage: JCamelMessage, headers: Map[String, Any]): CamelMessage = CamelMessage(camelMessage.getBody, headers ++ camelMessage.getHeaders)

}

/**
 * Positive acknowledgement message (used for application-acknowledged message receipts).
 * When `autoack` is set to false in the [[akka.camel.Consumer]], you can send an `Ack` to the sender of the CamelMessage.
 * @author Martin Krasser
 */
case object Ack {
  /** Java API to get the Ack singleton */
  def getInstance = this
}

/**
 * An exception indicating that the exchange to the camel endpoint failed.
 * It contains the failure cause obtained from Exchange.getException and the headers from either the Exchange.getIn
 * message or Exchange.getOut message, depending on the exchange pattern.
 *
 */
class AkkaCamelException private[akka] (cause: Throwable, val headers: Map[String, Any])
  extends AkkaException(cause.getMessage, cause) {
  def this(cause: Throwable) = this(cause, Map.empty)
}
