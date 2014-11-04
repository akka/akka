/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshallers.xml

import scala.collection.immutable

import java.io.{ ByteArrayInputStream, InputStreamReader }

import akka.http.marshalling._
import akka.http.model.MediaType
import akka.http.model.MediaTypes._
import akka.http.unmarshalling._
import akka.stream.FlowMaterializer

import scala.concurrent.ExecutionContext
import scala.xml.{ XML, NodeSeq }

trait ScalaXmlSupport {
  implicit def NodeSeqMarshallers(implicit ec: ExecutionContext): ToEntityMarshallers[NodeSeq] =
    Marshallers(ScalaXmlSupport.nodeSeqMediaTypes: _*)(nodeSeqMarshaller)

  def nodeSeqMarshaller(mediaType: MediaType)(implicit ec: ExecutionContext): ToEntityMarshaller[NodeSeq] =
    Marshaller.StringMarshaller.wrap(mediaType)(_.toString())

  implicit def nodeSeqUnmarshaller(implicit fm: FlowMaterializer,
                                   ec: ExecutionContext): FromEntityUnmarshaller[NodeSeq] =
    nodeSeqUnmarshaller(ScalaXmlSupport.nodeSeqMediaTypes: _*)

  def nodeSeqUnmarshaller(mediaTypes: MediaType*)(implicit fm: FlowMaterializer,
                                                  ec: ExecutionContext): FromEntityUnmarshaller[NodeSeq] =
    Unmarshaller.byteArrayUnmarshaller.mapWithCharset { (bytes, charset) ⇒
      val parser = XML.parser
      try parser.setProperty("http://apache.org/xml/properties/locale", java.util.Locale.ROOT)
      catch {
        case e: org.xml.sax.SAXNotRecognizedException ⇒ // property is not needed
      }
      val reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset.nioCharset)
      XML.withSAXParser(parser).load(reader): NodeSeq // blocking call! Ideally we'd have a `loadToFuture`
    }.filterMediaType(mediaTypes: _*)
}
object ScalaXmlSupport extends ScalaXmlSupport {
  val nodeSeqMediaTypes: immutable.Seq[MediaType] = List(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)
}