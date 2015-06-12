/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.xml

import java.io.{ ByteArrayInputStream, InputStreamReader }
import javax.xml.parsers.{ SAXParserFactory, SAXParser }
import scala.collection.immutable
import scala.xml.{ XML, NodeSeq }
import akka.stream.FlowMaterializer
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import MediaTypes._

trait ScalaXmlSupport {
  implicit def defaultNodeSeqMarshaller: ToEntityMarshaller[NodeSeq] =
    Marshaller.oneOf(ScalaXmlSupport.nodeSeqContentTypes.map(nodeSeqMarshaller): _*)

  def nodeSeqMarshaller(contentType: ContentType): ToEntityMarshaller[NodeSeq] =
    Marshaller.StringMarshaller.wrap(contentType)(_.toString())

  implicit def defaultNodeSeqUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[NodeSeq] =
    nodeSeqUnmarshaller(ScalaXmlSupport.nodeSeqContentTypeRanges: _*)

  def nodeSeqUnmarshaller(ranges: ContentTypeRange*)(implicit fm: FlowMaterializer): FromEntityUnmarshaller[NodeSeq] =
    Unmarshaller.byteArrayUnmarshaller.forContentTypes(ranges: _*).mapWithCharset { (bytes, charset) ⇒
      if (bytes.length > 0) {
        val reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset.nioCharset)
        XML.withSAXParser(createSAXParser()).load(reader): NodeSeq // blocking call! Ideally we'd have a `loadToFuture`
      } else NodeSeq.Empty
    }

  /**
   * Provides a SAXParser for the NodeSeqUnmarshaller to use. Override to provide a custom SAXParser implementation.
   * Will be called once for for every request to be unmarshalled. The default implementation calls [[ScalaXmlSupport.createSaferSAXParser]].
   * @return
   */
  protected def createSAXParser(): SAXParser = ScalaXmlSupport.createSaferSAXParser()
}
object ScalaXmlSupport extends ScalaXmlSupport {
  val nodeSeqContentTypes: immutable.Seq[ContentType] = List(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)
  val nodeSeqContentTypeRanges: immutable.Seq[ContentTypeRange] = nodeSeqContentTypes.map(ContentTypeRange(_))

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