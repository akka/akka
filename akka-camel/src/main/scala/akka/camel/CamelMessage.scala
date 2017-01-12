/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.camel

import java.util.{ Map ⇒ JMap, Set ⇒ JSet }
import javax.activation.DataHandler
import org.apache.camel.{ CamelContext, Message ⇒ JCamelMessage, StreamCache }
import akka.AkkaException
import scala.reflect.ClassTag
import scala.runtime.ScalaRunTime
import scala.util.Try
import scala.collection.JavaConversions._
import akka.dispatch.Mapper

/**
 * An immutable representation of a Camel message.
 */
@deprecated("Akka Camel is deprecated in favour of 'Alpakka', the Akka Streams based collection of integrations to various endpoints (including Camel).", since = "2.5.0")
class CamelMessage(val body: Any, val headers: Map[String, Any], val attachments: Map[String, DataHandler]) extends Serializable with Product {
  def this(body: Any, headers: JMap[String, Any]) = this(body, headers.toMap, Map.empty[String, DataHandler]) //Java
  def this(body: Any, headers: JMap[String, Any], attachments: JMap[String, DataHandler]) = this(body, headers.toMap, attachments.toMap) //Java
  def this(body: Any, headers: Map[String, Any]) = this(body, headers.toMap, Map.empty[String, DataHandler])

  def copy(body: Any = this.body, headers: Map[String, Any] = this.headers): CamelMessage = CamelMessage(body, headers, this.attachments)

  override def toString: String = "CamelMessage(%s, %s, %s)" format (body, headers, attachments)

  /**
   * Returns those headers from this message whose name is contained in <code>names</code>.
   */
  def headers(names: Set[String]): Map[String, Any] = headers filterKeys names

  /**
   * Java API: Returns those headers from this message whose name is contained in <code>names</code>.
   * The returned headers map is backed up by an immutable headers map. Any attempt to modify
   * the returned map will throw an exception.
   */
  def getHeaders(names: JSet[String]): JMap[String, Any] = headers(names.toSet)

  /**
   * Java API: Returns all headers from this message. The returned headers map is backed up by this
   * message's immutable headers map. Any attempt to modify the returned map will throw an
   * exception.
   */
  def getHeaders: JMap[String, Any] = headers

  /**
   * Java API: Creates a new CamelMessage with given <code>headers</code>. A copy of the headers map is made.
   */
  def withHeaders[A](headers: JMap[String, A]): CamelMessage = copy(this.body, headers.toMap)

  /**
   * Returns the header by given <code>name</code> parameter in a [[scala.util.Try]]. The header is  converted to type <code>T</code>, which is returned
   * in a [[scala.util.Success]]. If an exception occurs during the conversion to the type <code>T</code> or when the header cannot be found,
   * the exception is returned in a [[scala.util.Failure]].
   *
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   *
   */
  def headerAs[T](name: String)(implicit t: ClassTag[T], camelContext: CamelContext): Try[T] =
    Try(headers.get(name).map(camelContext.getTypeConverter.mandatoryConvertTo[T](t.runtimeClass.asInstanceOf[Class[T]], _)).getOrElse(throw new NoSuchElementException(name)))

  /**
   * Java API: Returns the header by given <code>name</code> parameter. The header is  converted to type <code>T</code> as defined by the <code>clazz</code> parameter.
   * An exception is thrown when the conversion to the type <code>T</code> fails or when the header cannot be found.
   *
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def getHeaderAs[T](name: String, clazz: Class[T], camelContext: CamelContext): T = headerAs[T](name)(ClassTag(clazz), camelContext).get

  /**
   * Returns a new CamelMessage with a transformed body using a <code>transformer</code> function.
   * This method will throw a [[java.lang.ClassCastException]] if the body cannot be mapped to type A.
   */
  def mapBody[A, B](transformer: A ⇒ B): CamelMessage = copy(body = transformer(body.asInstanceOf[A]))

  /**
   * Java API: Returns a new CamelMessage with a transformed body using a <code>transformer</code> function.
   * This method will throw a [[java.lang.ClassCastException]] if the body cannot be mapped to type A.
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
   * Java API: Returns the body of the message converted to the type as given by the <code>clazz</code>
   * parameter. Conversion is done using Camel's type converter. The type converter is obtained
   * from the CamelContext that is passed in.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
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
   * Java API: Returns a new CamelMessage with a new body, while keeping the same headers.
   */
  def withBody[T](body: T): CamelMessage = copy(body = body)
  /**
   * Creates a CamelMessage with current <code>body</code> converted to type <code>T</code>.
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def withBodyAs[T](implicit t: ClassTag[T], camelContext: CamelContext): CamelMessage = withBodyAs(t.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Java API: Creates a CamelMessage with current <code>body</code> converted to type <code>clazz</code>.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   */
  def withBodyAs[T](clazz: Class[T])(implicit camelContext: CamelContext): CamelMessage = copy(body = getBodyAs(clazz, camelContext))

  /**
   * Returns those attachments from this message whose name is contained in <code>names</code>.
   */
  def attachments(names: Set[String]): Map[String, DataHandler] = attachments filterKeys names

  /**
   * Java API: Returns those attachments from this message whose name is contained in <code>names</code>.
   * The returned headers map is backed up by an immutable headers map. Any attempt to modify
   * the returned map will throw an exception.
   */
  def getAttachments(names: JSet[String]): JMap[String, DataHandler] = attachments(names.toSet)

  /**
   * Java API: Returns all attachments from this message. The returned attachments map is backed up by this
   * message's immutable headers map. Any attempt to modify the returned map will throw an
   * exception.
   */
  def getAttachments: JMap[String, DataHandler] = attachments

  /**
   * Java API: Creates a new CamelMessage with given <code>attachments</code>. A copy of the attachments map is made.
   */
  def withAttachments(attachments: JMap[String, DataHandler]): CamelMessage = CamelMessage(this.body, this.headers, attachments.toMap)

  /**
   * SCALA API: Creates a new CamelMessage with given <code>attachments</code>.
   */
  def withAttachments(attachments: Map[String, DataHandler]): CamelMessage = CamelMessage(this.body, this.headers, attachments)

  /**
   * Indicates whether some other object is "equal to" this one.
   */
  override def equals(that: Any): Boolean =
    that match {
      case that: CamelMessage if canEqual(that) ⇒
        this.body == that.body &&
          this.headers == that.headers &&
          this.attachments == that.attachments
      case _ ⇒ false
    }

  /**
   * Returns a hash code value for the object.
   */
  override def hashCode(): Int = ScalaRunTime._hashCode(this)

  /**
   * Returns the n-th element of this product, 0-based.
   */
  override def productElement(n: Int): Any = n match {
    case 0 ⇒ body
    case 1 ⇒ headers
    case 2 ⇒ attachments
  }

  /**
   * Returns the size of this product.
   */
  override def productArity: Int = 3

  /**
   * Indicates if some other object can be compared (based on type).
   * This method should be called from every well-designed equals method that is open to be overridden in a subclass.
   */
  override def canEqual(that: Any): Boolean = that match {
    case _: CamelMessage ⇒ true
    case _               ⇒ false
  }
}

/**
 * Companion object of CamelMessage class.
 */
object CamelMessage extends ((Any, Map[String, Any]) ⇒ CamelMessage) {

  /**
   * Returns a new CamelMessage based on the <code>body</code> and <code>headers</code>.
   */
  def apply(body: Any, headers: Map[String, Any]): CamelMessage = new CamelMessage(body, headers, Map.empty[String, DataHandler])

  /**
   * Returns a new CamelMessage based on the <code>body</code>, <code>headers</code> and <code>attachments</code>.
   */
  def apply(body: Any, headers: Map[String, Any], attachments: Map[String, DataHandler]): CamelMessage = new CamelMessage(body, headers, attachments)

  /**
   * Returns <code>Some(body, headers)</code>.
   */
  def unapply(camelMessage: CamelMessage): Option[(Any, Map[String, Any])] = Some((camelMessage.body, camelMessage.headers))

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
    case body               ⇒ CamelMessage(body, Map.empty[String, Any])
  }

  /**
   * Creates a new CamelMessage object from the Camel message.
   *
   * @param headers additional headers to set on the created CamelMessage in addition to those
   *                in the Camel message.
   */
  private[camel] def from(camelMessage: JCamelMessage, headers: Map[String, Any]): CamelMessage =
    CamelMessage(camelMessage.getBody, headers ++ camelMessage.getHeaders, camelMessage.getAttachments.toMap)

  /**
   * Creates a new CamelMessageWithAttachments object from the Camel message.
   *
   * @param headers additional headers to set on the created CamelMessageWithAttachments in addition to those
   *                in the Camel message.
   * @param attachments additional attachments to set on the created CamelMessageWithAttachments in addition to those
   *                in the Camel message.
   */
  private[camel] def from(camelMessage: JCamelMessage, headers: Map[String, Any], attachments: Map[String, DataHandler]): CamelMessage =
    CamelMessage(camelMessage.getBody, headers ++ camelMessage.getHeaders, attachments ++ camelMessage.getAttachments)

  /**
   * INTERNAL API
   * copies the content of this CamelMessageWithAttachments to an Apache Camel Message.
   */
  private[camel] def copyContent(from: CamelMessage, to: JCamelMessage): Unit = {
    to.setBody(from.body)
    for ((name, value) ← from.headers) to.getHeaders.put(name, value.asInstanceOf[AnyRef])
    to.getAttachments.putAll(from.getAttachments)
  }
}

/**
 * Positive acknowledgement message (used for application-acknowledged message receipts).
 * When `autoAck` is set to false in the [[akka.camel.Consumer]], you can send an `Ack` to the sender of the CamelMessage.
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
