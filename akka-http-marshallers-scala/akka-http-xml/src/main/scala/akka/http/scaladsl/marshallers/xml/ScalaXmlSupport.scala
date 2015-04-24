/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.xml

import java.io.{ ByteArrayInputStream, InputStreamReader }
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.xml.{ XML, NodeSeq }
import akka.stream.FlowMaterializer
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import MediaTypes._

trait ScalaXmlSupport {
  implicit def defaultNodeSeqMarshaller(implicit ec: ExecutionContext): ToEntityMarshaller[NodeSeq] =
    Marshaller.oneOf(ScalaXmlSupport.nodeSeqContentTypes.map(nodeSeqMarshaller): _*)

  def nodeSeqMarshaller(contentType: ContentType)(implicit ec: ExecutionContext): ToEntityMarshaller[NodeSeq] =
    Marshaller.StringMarshaller.wrap(contentType)(_.toString())

  implicit def defaultNodeSeqUnmarshaller(implicit fm: FlowMaterializer,
                                          ec: ExecutionContext): FromEntityUnmarshaller[NodeSeq] =
    nodeSeqUnmarshaller(ScalaXmlSupport.nodeSeqContentTypeRanges: _*)

  def nodeSeqUnmarshaller(ranges: ContentTypeRange*)(implicit fm: FlowMaterializer,
                                                     ec: ExecutionContext): FromEntityUnmarshaller[NodeSeq] =
    Unmarshaller.byteArrayUnmarshaller.forContentTypes(ranges: _*).mapWithCharset { (bytes, charset) ⇒
      if (bytes.length > 0) {
        val parser = XML.parser
        try parser.setProperty("http://apache.org/xml/properties/locale", java.util.Locale.ROOT)
        catch { case e: org.xml.sax.SAXNotRecognizedException ⇒ /* property is not needed */ }
        val reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset.nioCharset)
        XML.withSAXParser(parser).load(reader): NodeSeq // blocking call! Ideally we'd have a `loadToFuture`
      } else NodeSeq.Empty
    }
}
object ScalaXmlSupport extends ScalaXmlSupport {
  val nodeSeqContentTypes: immutable.Seq[ContentType] = List(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)
  val nodeSeqContentTypeRanges: immutable.Seq[ContentTypeRange] = nodeSeqContentTypes.map(ContentTypeRange(_))
}