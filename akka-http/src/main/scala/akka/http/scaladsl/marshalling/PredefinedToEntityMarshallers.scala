/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import java.nio.CharBuffer
import akka.http.impl.model.parser.CharacterClasses
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.impl.util.StringRendering
import akka.util.ByteString

trait PredefinedToEntityMarshallers extends MultipartMarshallers {

  implicit val ByteArrayMarshaller: ToEntityMarshaller[Array[Byte]] = byteArrayMarshaller(`application/octet-stream`)
  def byteArrayMarshaller(mediaType: MediaType, charset: HttpCharset): ToEntityMarshaller[Array[Byte]] = {
    val ct = ContentType(mediaType, charset)
    Marshaller.withFixedCharset(ct.mediaType, ct.definedCharset.get) { bytes ⇒ HttpEntity(ct, bytes) }
  }
  def byteArrayMarshaller(mediaType: MediaType): ToEntityMarshaller[Array[Byte]] = {
    val ct = ContentType(mediaType)
    // since we don't want to recode we simply ignore the charset determined by content negotiation here
    Marshaller.withOpenCharset(ct.mediaType) { (bytes, _) ⇒ HttpEntity(ct, bytes) }
  }

  implicit val ByteStringMarshaller: ToEntityMarshaller[ByteString] = byteStringMarshaller(`application/octet-stream`)
  def byteStringMarshaller(mediaType: MediaType, charset: HttpCharset): ToEntityMarshaller[ByteString] = {
    val ct = ContentType(mediaType, charset)
    Marshaller.withFixedCharset(ct.mediaType, ct.definedCharset.get) { bytes ⇒ HttpEntity(ct, bytes) }
  }
  def byteStringMarshaller(mediaType: MediaType): ToEntityMarshaller[ByteString] = {
    val ct = ContentType(mediaType)
    // since we don't want to recode we simply ignore the charset determined by content negotiation here
    Marshaller.withOpenCharset(ct.mediaType) { (bytes, _) ⇒ HttpEntity(ct, bytes) }
  }

  implicit val CharArrayMarshaller: ToEntityMarshaller[Array[Char]] = charArrayMarshaller(`text/plain`)
  def charArrayMarshaller(mediaType: MediaType): ToEntityMarshaller[Array[Char]] =
    Marshaller.withOpenCharset(mediaType) { (value, charset) ⇒
      if (value.length > 0) {
        val charBuffer = CharBuffer.wrap(value)
        val byteBuffer = charset.nioCharset.encode(charBuffer)
        val array = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(array)
        HttpEntity(ContentType(mediaType, charset), array)
      } else HttpEntity.Empty
    }

  implicit val StringMarshaller: ToEntityMarshaller[String] = stringMarshaller(`text/plain`)
  def stringMarshaller(mediaType: MediaType): ToEntityMarshaller[String] =
    Marshaller.withOpenCharset(mediaType) { (s, cs) ⇒ HttpEntity(ContentType(mediaType, cs), s) }

  implicit val FormDataMarshaller: ToEntityMarshaller[FormData] =
    Marshaller.withOpenCharset(`application/x-www-form-urlencoded`) { (formData, charset) ⇒
      val query = Uri.Query(formData.fields: _*)
      val string = UriRendering.renderQuery(new StringRendering, query, charset.nioCharset, CharacterClasses.unreserved).get
      HttpEntity(ContentType(`application/x-www-form-urlencoded`, charset), string)
    }

  implicit val HttpEntityMarshaller: ToEntityMarshaller[MessageEntity] = Marshaller strict { value ⇒
    Marshalling.WithFixedCharset(value.contentType.mediaType, value.contentType.charset, () ⇒ value)
  }
}

object PredefinedToEntityMarshallers extends PredefinedToEntityMarshallers

