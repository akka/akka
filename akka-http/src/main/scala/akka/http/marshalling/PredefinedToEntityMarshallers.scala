/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import java.nio.CharBuffer

import akka.http.model.MediaTypes._
import akka.http.model._
import akka.http.util.StringRendering
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, Future }
import scala.xml.NodeSeq

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

  implicit def nodeSeqMarshaller(mediaType: MediaType)(implicit ec: ExecutionContext): ToEntityMarshaller[NodeSeq] =
    StringMarshaller.wrap(mediaType)(_.toString())

  implicit val FormDataMarshaller: ToEntityMarshaller[FormData] =
    Marshaller.withOpenCharset(`application/x-www-form-urlencoded`) { (formData, charset) ⇒
      val string = UriRendering.renderQuery(new StringRendering, Uri.Query(formData.fields: _*), charset.nioCharset).get
      HttpEntity(ContentType(`application/x-www-form-urlencoded`, charset), string)
    }

  implicit val HttpEntityMarshaller: ToEntityMarshaller[HttpEntity.Regular] = Marshaller { value ⇒
    // since we don't want to recode we simply ignore the charset determined by content negotiation here
    Future.successful(Marshalling.WithOpenCharset(value.contentType.mediaType, _ ⇒ value))
  }
}

object PredefinedToEntityMarshallers extends PredefinedToEntityMarshallers

