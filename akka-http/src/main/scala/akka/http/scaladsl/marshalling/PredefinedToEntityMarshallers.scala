/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import java.nio.CharBuffer

import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model._
import akka.util.ByteString

trait PredefinedToEntityMarshallers extends MultipartMarshallers {

  implicit val ByteArrayMarshaller: ToEntityMarshaller[Array[Byte]] = byteArrayMarshaller(`application/octet-stream`)
  def byteArrayMarshaller(contentType: ContentType): ToEntityMarshaller[Array[Byte]] =
    Marshaller.withFixedContentType(contentType) { bytes ⇒ HttpEntity(contentType, bytes) }

  implicit val ByteStringMarshaller: ToEntityMarshaller[ByteString] = byteStringMarshaller(`application/octet-stream`)
  def byteStringMarshaller(contentType: ContentType): ToEntityMarshaller[ByteString] =
    Marshaller.withFixedContentType(contentType) { bytes ⇒ HttpEntity(contentType, bytes) }

  implicit val CharArrayMarshaller: ToEntityMarshaller[Array[Char]] = charArrayMarshaller(`text/plain`)
  def charArrayMarshaller(mediaType: MediaType.WithOpenCharset): ToEntityMarshaller[Array[Char]] =
    Marshaller.withOpenCharset(mediaType) { (value, charset) ⇒ marshalCharArray(value, mediaType withCharset charset) }
  def charArrayMarshaller(mediaType: MediaType.WithFixedCharset): ToEntityMarshaller[Array[Char]] =
    Marshaller.withFixedContentType(mediaType) { value ⇒ marshalCharArray(value, mediaType) }

  private def marshalCharArray(value: Array[Char], contentType: ContentType.NonBinary): HttpEntity.Strict =
    if (value.length > 0) {
      val charBuffer = CharBuffer.wrap(value)
      val byteBuffer = contentType.charset.nioCharset.encode(charBuffer)
      val array = new Array[Byte](byteBuffer.remaining())
      byteBuffer.get(array)
      HttpEntity(contentType, array)
    } else HttpEntity.Empty

  implicit val DoneMarshaller: ToEntityMarshaller[akka.Done] =
    Marshaller.withFixedContentType(`text/plain(UTF-8)`) { done ⇒
      HttpEntity(`text/plain(UTF-8)`, "")
    }

  implicit val StringMarshaller: ToEntityMarshaller[String] = stringMarshaller(`text/plain`)
  def stringMarshaller(mediaType: MediaType.WithOpenCharset): ToEntityMarshaller[String] =
    Marshaller.withOpenCharset(mediaType) { (s, cs) ⇒ HttpEntity(mediaType withCharset cs, s) }
  def stringMarshaller(mediaType: MediaType.WithFixedCharset): ToEntityMarshaller[String] =
    Marshaller.withFixedContentType(mediaType) { s ⇒ HttpEntity(mediaType, s) }

  implicit val FormDataMarshaller: ToEntityMarshaller[FormData] =
    Marshaller.withOpenCharset(`application/x-www-form-urlencoded`) { _ toEntity _ }

  implicit val MessageEntityMarshaller: ToEntityMarshaller[MessageEntity] =
    Marshaller strict { value ⇒ Marshalling.WithFixedContentType(value.contentType, () ⇒ value) }
}

object PredefinedToEntityMarshallers extends PredefinedToEntityMarshallers

