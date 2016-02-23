/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.xml

import java.io.{ ByteArrayInputStream, InputStreamReader }
import javax.xml.parsers.{ SAXParserFactory, SAXParser }
import scala.collection.immutable
import scala.xml.{ XML, NodeSeq }
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import MediaTypes._

trait ScalaXmlSupport {
  implicit def defaultNodeSeqMarshaller: ToEntityMarshaller[NodeSeq] =
    Marshaller.oneOf(ScalaXmlSupport.nodeSeqMediaTypes.map(nodeSeqMarshaller): _*)

  def nodeSeqMarshaller(mediaType: MediaType.NonBinary): ToEntityMarshaller[NodeSeq] =
    Marshaller.StringMarshaller.wrap(mediaType)(_.toString())

  implicit def defaultNodeSeqUnmarshaller: FromEntityUnmarshaller[NodeSeq] =
    nodeSeqUnmarshaller(ScalaXmlSupport.nodeSeqContentTypeRanges: _*)

  def nodeSeqUnmarshaller(ranges: ContentTypeRange*): FromEntityUnmarshaller[NodeSeq] =
    Unmarshaller.byteArrayUnmarshaller.forContentTypes(ranges: _*).mapWithCharset { (bytes, charset) ⇒
      if (bytes.length > 0) {
        val reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset.nioCharset)
        XML.withSAXParser(createSAXParser()).load(reader): NodeSeq // blocking call! Ideally we'd have a `loadToFuture`
      } else NodeSeq.Empty
    }

  /**
   * Provides a SAXParser for the NodeSeqUnmarshaller to use. Override to provide a custom SAXParser implementation.
   * Will be called once for for every request to be unmarshalled. The default implementation calls `ScalaXmlSupport.createSaferSAXParser`.
   */
  protected def createSAXParser(): SAXParser = ScalaXmlSupport.createSaferSAXParser()
}
object ScalaXmlSupport extends ScalaXmlSupport {
  val nodeSeqMediaTypes: immutable.Seq[MediaType.NonBinary] = List(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)
  val nodeSeqContentTypeRanges: immutable.Seq[ContentTypeRange] = nodeSeqMediaTypes.map(ContentTypeRange(_))

  /** Creates a safer SAXParser. */
  def createSaferSAXParser(): SAXParser = {
    val factory = SAXParserFactory.newInstance()
    import com.sun.org.apache.xerces.internal.impl.Constants
    import javax.xml.XMLConstants

    factory.setFeature(Constants.SAX_FEATURE_PREFIX + Constants.EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
    factory.setFeature(Constants.SAX_FEATURE_PREFIX + Constants.EXTERNAL_PARAMETER_ENTITIES_FEATURE, false)
    factory.setFeature(Constants.XERCES_FEATURE_PREFIX + Constants.DISALLOW_DOCTYPE_DECL_FEATURE, true)
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    val parser = factory.newSAXParser()
    try {
      parser.setProperty("http://apache.org/xml/properties/locale", java.util.Locale.ROOT)
    } catch {
      case e: org.xml.sax.SAXNotRecognizedException ⇒ // property is not needed
    }
    parser
  }
}