/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.unmarshalling

import akka.stream.FlowMaterializer
import akka.util.ByteString
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model._

trait PredefinedFromEntityUnmarshallers extends MultipartUnmarshallers {

  implicit def byteStringUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[ByteString] =
    Unmarshaller(_ ⇒ {
      case HttpEntity.Strict(_, data) ⇒ FastFuture.successful(data)
      case entity                     ⇒ entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
    })

  implicit def byteArrayUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[Array[Byte]] =
    byteStringUnmarshaller.map(_.toArray[Byte])

  implicit def charArrayUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[Array[Char]] =
    byteStringUnmarshaller(fm) mapWithInput { (entity, bytes) ⇒
      val charBuffer = entity.contentType.charset.nioCharset.decode(bytes.asByteBuffer)
      val array = new Array[Char](charBuffer.length())
      charBuffer.get(array)
      array
    }

  implicit def stringUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[String] =
    byteStringUnmarshaller(fm) mapWithInput { (entity, bytes) ⇒
      // FIXME: add `ByteString::decodeString(java.nio.Charset): String` overload!!!
      bytes.decodeString(entity.contentType.charset.nioCharset.name) // ouch!!!
    }

  implicit def defaultUrlEncodedFormDataUnmarshaller(implicit fm: FlowMaterializer): FromEntityUnmarshaller[FormData] =
    urlEncodedFormDataUnmarshaller(MediaTypes.`application/x-www-form-urlencoded`)
  def urlEncodedFormDataUnmarshaller(ranges: ContentTypeRange*)(implicit fm: FlowMaterializer): FromEntityUnmarshaller[FormData] =
    stringUnmarshaller.forContentTypes(ranges: _*).mapWithInput { (entity, string) ⇒
      try {
        val nioCharset = entity.contentType.definedCharset.getOrElse(HttpCharsets.`UTF-8`).nioCharset
        val query = Uri.Query(string, nioCharset)
        FormData(query)
      } catch {
        case IllegalUriException(info) ⇒
          throw new IllegalArgumentException(info.formatPretty.replace("Query,", "form content,"))
      }
    }
}

object PredefinedFromEntityUnmarshallers extends PredefinedFromEntityUnmarshallers