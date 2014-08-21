/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import java.io.{ ByteArrayInputStream, InputStreamReader }

import scala.concurrent.{ Future, ExecutionContext }
import scala.xml.{ XML, NodeSeq }
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.http.model._
import MediaTypes._

trait PredefinedFromEntityUnmarshallers extends MultipartUnmarshallers {

  implicit def byteStringUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[ByteString] =
    Unmarshaller { entity ⇒
      if (entity.isKnownEmpty) Future.successful(Unmarshalling.Success(ByteString.empty))
      else Flow(entity.dataBytes(fm)).fold(ByteString.empty)(_ ++ _).map(Unmarshalling.Success(_)).toFuture()(fm)
    }

  implicit def byteArrayUnmarshaller(implicit fm: FlowMaterializer,
                                     ec: ExecutionContext): FromEntityUnmarshaller[Array[Byte]] =
    byteStringUnmarshaller.map(_.toArray[Byte])

  implicit def charArrayUnmarshaller(implicit fm: FlowMaterializer,
                                     ec: ExecutionContext): FromEntityUnmarshaller[Array[Char]] =
    byteStringUnmarshaller(fm) mapWithInput { (entity, bytes) ⇒
      val charBuffer = entity.contentType.charset.nioCharset.decode(bytes.asByteBuffer)
      val array = new Array[Char](charBuffer.length())
      charBuffer.get(array)
      array
    }

  implicit def stringUnmarshaller(implicit fm: FlowMaterializer,
                                  ec: ExecutionContext): FromEntityUnmarshaller[String] =
    byteStringUnmarshaller(fm) mapWithInput { (entity, bytes) ⇒
      // FIXME: add `ByteString::decodeString(java.nio.Charset): String` overload!!!
      bytes.decodeString(entity.contentType.charset.nioCharset.name) // ouch!!!
    }

  private val nodeSeqMediaTypes = List(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)
  implicit def nodeSeqUnmarshaller(implicit fm: FlowMaterializer,
                                   ec: ExecutionContext): FromEntityUnmarshaller[NodeSeq] =
    byteArrayUnmarshaller flatMapWithInput { (entity, bytes) ⇒
      if (nodeSeqMediaTypes contains entity.contentType.mediaType) {
        val parser = XML.parser
        try parser.setProperty("http://apache.org/xml/properties/locale", java.util.Locale.ROOT)
        catch {
          case e: org.xml.sax.SAXNotRecognizedException ⇒ // property is not needed
        }
        val reader = new InputStreamReader(new ByteArrayInputStream(bytes), entity.contentType.charset.nioCharset)
        Unmarshalling.Success(XML.withSAXParser(parser).load(reader))
      } else Unmarshalling.UnsupportedContentType(nodeSeqMediaTypes map (ContentTypeRange(_)))
    }
}

object PredefinedFromEntityUnmarshallers extends PredefinedFromEntityUnmarshallers