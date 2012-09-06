/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import java.util.{ Map ⇒ JMap, Set ⇒ JSet }

import scala.collection.JavaConversions._

import akka.japi.{ Function ⇒ JFunction }
import org.apache.camel.{ CamelContext, Message ⇒ JCamelMessage }
import akka.AkkaException
import scala.reflect.ClassTag
import javax.activation.{ DataHandler, DataSource }
import akka.util.ByteString
import java.io.{ IOException, BufferedInputStream, ByteArrayInputStream, ByteArrayOutputStream }
import io.{ Codec, Source }

/**
 * An immutable representation of a Camel message.
 *
 * @author Martin Krasser
 */
case class CamelMessage(body: Any, headers: Map[String, Any], attachments: Map[String, Attachment] = Map()) {

  def this(body: Any, headers: JMap[String, Any]) = this(body, headers.toMap, Map[String, Attachment]()) //for Java
  def this(body: Any, headers: JMap[String, Any], attachments: JMap[String, Attachment]) = this(body, headers.toMap, attachments.toMap) //for Java

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

  /**
   * Returns the header with given <code>name</code> converted to type <code>T</code>. Throws
   * <code>NoSuchElementException</code> if the header doesn't exist.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   *
   */
  def headerAs[T](name: String)(implicit t: ClassTag[T], camelContext: CamelContext): Option[T] = header(name).map(camelContext.getTypeConverter.mandatoryConvertTo[T](t.runtimeClass.asInstanceOf[Class[T]], _))

  /**
   * Returns the header with given <code>name</code> converted to type as given by the <code>clazz</code>
   * parameter. Throws <code>NoSuchElementException</code> if the header doesn't exist.
   * <p>
   * The CamelContext is accessible in a [[akka.camel.javaapi.UntypedConsumerActor]] and [[akka.camel.javaapi.UntypedProducerActor]]
   * using the `getCamelContext` method, and is available on the [[akka.camel.CamelExtension]].
   * <p>
   * Java API
   */
  def getHeaderAs[T](name: String, clazz: Class[T], camelContext: CamelContext): T = headerAs[T](name)(ClassTag(clazz), camelContext).get

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
  def getBodyAs[T](clazz: Class[T], camelContext: CamelContext): T = camelContext.getTypeConverter.mandatoryConvertTo[T](clazz, body)

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
  def withBodyAs[T](clazz: Class[T])(implicit camelContext: CamelContext): CamelMessage = withBody(getBodyAs(clazz, camelContext))

  /**
   * Returns those attachments from this message whose name is contained in <code>names</code>.
   */
  def attachments(names: Set[String]): Map[String, Attachment] = attachments filterKeys names

  /**
   * Returns those attachments from this message whose name is contained in <code>names</code>.
   * The returned attachments map is backed up by an immutable attachment map. Any attempt to modify
   * the returned map will throw an exception.
   * <p>
   * Java API
   */
  def getAttachments(names: JSet[String]): JMap[String, Attachment] = attachments(names.toSet)

  /**
   * Returns all attachments from this message. The returned attachments map is backed up by this
   * message's immutable attachments map. Any attempt to modify the returned map will throw an
   * exception.
   * <p>
   * Java API
   */
  def getAttachments: JMap[String, Attachment] = attachments

  /**
   * Returns the attachment with given <code>name</code>. Throws <code>NoSuchElementException</code>
   * if the attachment doesn't exist.
   */
  def attachment(name: String): Option[Attachment] = attachments.get(name)

  /**
   * Returns the attachment with given <code>name</code>. Throws <code>NoSuchElementException</code>
   * if the attachment doesn't exist.
   * <p>
   * Java API
   */
  def getAttachment(name: String): Attachment = attachments(name)

  /**
   * Creates a new CamelMessage with given <code>attachments</code>.
   */
  def withAttachments(attachments: Map[String, Attachment]): CamelMessage = copy(this.body, this.headers, attachments)

  /**
   * Creates a new CamelMessage with given <code>attachments</code>. A copy of the attachments map is made.
   * <p>
   * Java API
   */
  def withAttachments(attachments: JMap[String, Attachment]): CamelMessage = withAttachments(attachments.toMap)

  /**
   * Creates a new CamelMessage with given <code>attachments</code> added to the current attachments.
   */
  def addAttachments[A](attachments: Map[String, Attachment]): CamelMessage = copy(this.body, this.headers, this.attachments ++ attachments)

  /**
   * Creates a new CamelMessage with given <code>attachments</code> added to the current attachments.
   * A copy of the attachments map is made.
   * <p>
   * Java API
   */
  def addAttachments(attachments: JMap[String, Attachment]): CamelMessage = addAttachments(attachments.toMap)

  /**
   * Creates a new CamelMessage with the given <code>attachment</code> added to the current attachments.
   */
  def addAttachment(attachment: (String, Attachment)): CamelMessage = copy(this.body, this.headers, this.attachments + attachment)

  /**
   * Creates a new CamelMessage with the given attachment, represented by <code>name</code> and
   * <code>value</code> added to the existing attachments.
   * <p>
   * Java API
   */
  def addAttachment(name: String, value: Attachment): CamelMessage = addAttachment((name, value))

  /**
   * Creates a new CamelMessage where the attachment with given <code>attachmentName</code> is removed from
   * the existing attachments.
   */
  def withoutAttachment(attachmentName: String): CamelMessage = copy(this.body, this.headers, this.attachments - attachmentName)

  def copyContentTo(to: JCamelMessage): Unit = {
    to.setBody(this.body)
    for ((name, value) ← this.headers) to.getHeaders.put(name, value.asInstanceOf[AnyRef])
    for ((name, value) ← this.attachments) {
      to.getAttachments.put(name, value.createDataHandler)
    }
  }

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
  def from(camelMessage: JCamelMessage, headers: Map[String, Any]): CamelMessage = {
    val attachments = camelMessage.getAttachments.map {
      case (key, dataHandler) ⇒ {
        val in = new BufferedInputStream(dataHandler.getInputStream)
        try {
          var readin = new Array[Byte](8196)
          var read = 0
          val builder = ByteString.newBuilder
          while (-1 != read) {
            read = in.read(readin)
            if (read != -1) {
              builder.putBytes(readin, 0, read)
            }
          }
          val byteString = builder.result
          (key -> Attachment(dataHandler.getName, dataHandler.getContentType, byteString))
        } finally {
          in.close()
        }
      }
    }.toMap
    CamelMessage(camelMessage.getBody, headers ++ camelMessage.getHeaders, attachments)
  }
}

/**
 * Apache Camel uses [[javax.activation.DataHandler]] for an attachment.
 * DataHandlers are not immutable, so in akka-camel an Attachment keeps the bytes of
 * the original attachment in an immutable [[akka.util.ByteString]].
 */
case class Attachment(name: String, contentType: String, bytes: ByteString) {
  /**
   * Internal API.
   * Creates a DataHandler for the attachment. [[org.apache.camel.Message]] uses DataHandlers to support attachments.
   * The Attachment needs to be converted to a DataHandler when it is sent to Camel.
   */
  private[camel] def createDataHandler: DataHandler = {
    new DataHandler(new DataSource {
      private val bytesCopy = bytes.toArray
      def getName = name

      // according to DataHandler javadocs an IOException should be thrown if the handler does not support overwriting data.
      def getOutputStream = throw new IOException("Attachment DataHandler does not support overwriting the data")

      def getContentType = contentType

      def getInputStream = new ByteArrayInputStream(bytesCopy)
    })
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
 *
 */
class AkkaCamelException private[akka] (cause: Throwable, val headers: Map[String, Any])
  extends AkkaException(cause.getMessage, cause) {
  def this(cause: Throwable) = this(cause, Map.empty)
}
