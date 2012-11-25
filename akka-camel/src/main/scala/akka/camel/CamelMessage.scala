/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import java.util.{ Map ⇒ JMap, Set ⇒ JSet }
import org.apache.camel.{ CamelContext, Message ⇒ JCamelMessage, StreamCache }
import akka.AkkaException
import scala.reflect.ClassTag
import scala.util.Try
import scala.collection.JavaConversions._
import akka.dispatch.Mapper

/**
 * An immutable representation of a Camel message.
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
   * Creates a new CamelMessage with given <code>headers</code>. A copy of the headers map is made.
   * <p>
   * Java API
   */
  def withHeaders[A](headers: JMap[String, A]): CamelMessage = copy(this.body, headers.toMap)

  /**
   * Returns the header by given <code>name</code> parameter in a [[scala.util.Try]]. The header is  converted to type <code>T</code>, which is returned
   * in a [[scala.util.Success]]. If an exception occurs during the conversion to the type <code>T</code> or when the header cannot be found,
   * the exception is returned in a [[scala.util.Failure]].
   *
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   *
   */
  def headerAs[T](name: String)(implicit t: ClassTag[T], camelContext: CamelContext): Try[T] =
    Try(headers.get(name).map(camelContext.getTypeConverter.mandatoryConvertTo[T](t.runtimeClass.asInstanceOf[Class[T]], _)).getOrElse(throw new NoSuchElementException(name)))

  /**
   * Returns the header by given <code>name</code> parameter. The header is  converted to type <code>T</code> as defined by the <code>clazz</code> parameter.
   * An exception is thrown when the conversion to the type <code>T</code> fails or when the header cannot be found.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   * <p>
   * Java API
   */
  def getHeaderAs[T](name: String, clazz: Class[T], camelContext: CamelContext): T = headerAs[T](name)(ClassTag(clazz), camelContext).get

  /**
   * Returns a new CamelMessage with a transformed body using a <code>transformer</code> function.
   * This method will throw a [[java.lang.ClassCastException]] if the body cannot be mapped to type A.
   */
  def mapBody[A, B](transformer: A ⇒ B): CamelMessage = copy(body = transformer(body.asInstanceOf[A]))

  /**
   * Returns a new CamelMessage with a transformed body using a <code>transformer</code> function.
   * This method will throw a [[java.lang.ClassCastException]] if the body cannot be mapped to type A.
   * <p>
   * Java API
   */
  def mapBody[A, B](transformer: Mapper[A, B]): CamelMessage = copy(body = transformer(body.asInstanceOf[A]))

  /**
   * Returns the body of the message converted to the type <code>T</code>. Conversion is done
   * using Camel's type converter. The type converter is obtained from the CamelContext that is passed in.
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def bodyAs[T](implicit t: ClassTag[T], camelContext: CamelContext): T = getBodyAs(t.runtimeClass.asInstanceOf[Class[T]], camelContext)

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
  def getBodyAs[T](clazz: Class[T], camelContext: CamelContext): T = {
    val result = camelContext.getTypeConverter.mandatoryConvertTo[T](clazz, body)
    // to be able to re-read a StreamCache we must "undo" the side effect by resetting the StreamCache
    resetStreamCache()
    result
  }

  /**
   * Reset StreamCache body. Nothing is done if the body is not a StreamCache.
   * See http://camel.apache.org/stream-caching.html
   */
  def resetStreamCache(): Unit = body match {
    case stream: StreamCache ⇒ stream.reset
    case _                   ⇒
  }

  /**
   * Returns a new CamelMessage with a new body, while keeping the same headers.
   * <p>
   * Java API
   */
  def withBody[T](body: T) = this.copy(body = body)
  /**
   * Creates a CamelMessage with current <code>body</code> converted to type <code>T</code>.
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def withBodyAs[T](implicit t: ClassTag[T], camelContext: CamelContext): CamelMessage = withBodyAs(t.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Creates a CamelMessage with current <code>body</code> converted to type <code>clazz</code>.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   * <p>
   * Java API
   */
  def withBodyAs[T](clazz: Class[T])(implicit camelContext: CamelContext): CamelMessage = copy(body = getBodyAs(clazz, camelContext))

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
  private[camel] def canonicalize(msg: Any) = msg match {
    case mobj: CamelMessage ⇒ mobj
    case body               ⇒ CamelMessage(body, Map.empty)
  }

  /**
   * Creates a new CamelMessage object from the Camel message.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  private[camel] def from(camelMessage: JCamelMessage, headers: Map[String, Any]): CamelMessage = CamelMessage(camelMessage.getBody, headers ++ camelMessage.getHeaders)

  /**
   * For internal use only.
   * copies the content of this CamelMessage to an Apache Camel Message.
   *
   */
  private[camel] def copyContent(from: CamelMessage, to: JCamelMessage): Unit = {
    to.setBody(from.body)
    for ((name, value) ← from.headers) to.getHeaders.put(name, value.asInstanceOf[AnyRef])
  }
}

/**
 * Positive acknowledgement message (used for application-acknowledged message receipts).
 * When `autoAck` is set to false in the [[akka.camel.Consumer]], you can send an `Ack` to the sender of the CamelMessage.
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
 */
class AkkaCamelException private[akka] (cause: Throwable, val headers: Map[String, Any])
  extends AkkaException(cause.getMessage, cause) {
  def this(cause: Throwable) = this(cause, Map.empty)
}
