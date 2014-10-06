/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import java.io.{ ByteArrayInputStream, InputStreamReader }
import scala.concurrent.ExecutionContext
import scala.xml.{ XML, NodeSeq }
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.http.util.FastFuture
import akka.http.model._
import MediaTypes._

trait PredefinedFromEntityUnmarshallers extends MultipartUnmarshallers {

  implicit def byteStringUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[ByteString] =
    Unmarshaller {
      case HttpEntity.Strict(_, data) ⇒ FastFuture.successful(data)
      case entity                     ⇒ Flow(entity.dataBytes(fm)).fold(ByteString.empty)(_ ++ _).toFuture()
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
        FastFuture.successful(XML.withSAXParser(parser).load(reader)) // blocking call! Ideally we'd have a `loadToFuture`
      } else UnmarshallingError.UnsupportedContentType(nodeSeqMediaTypes map (ContentTypeRange(_)))
    }

  implicit def urlEncodedFormDataUnmarshaller(implicit fm: FlowMaterializer,
                                              ec: ExecutionContext): FromEntityUnmarshaller[FormData] =
    stringUnmarshaller mapWithInput { (entity, string) ⇒
      try {
        val nioCharset = entity.contentType.definedCharset.getOrElse(HttpCharsets.`UTF-8`).nioCharset
        val query = Uri.Query(string, nioCharset)
        FormData(query)
      } catch {
        case ex: IllegalUriException ⇒
          throw new IllegalArgumentException(ex.info.formatPretty.replace("Query,", "form content,"))
      }
    }
}

object PredefinedFromEntityUnmarshallers extends PredefinedFromEntityUnmarshallers