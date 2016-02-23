/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.javadsl.model._
import akka.http.scaladsl.marshalling.{ ToResponseMarshaller, Marshaller ⇒ ScalaMarshaller }
import akka.http.impl.server.MarshallerImpl
import akka.http.scaladsl
import akka.japi.function
import akka.util.ByteString

/**
 * A collection of predefined marshallers.
 */
object Marshallers {
  /**
   * A marshaller that marshals a String to a `text/plain` using a charset as negotiated with the
   * peer.
   */
  def String: Marshaller[String] = MarshallerImpl(implicit ctx ⇒ implicitly[ToResponseMarshaller[String]])

  import akka.http.impl.util.JavaMapping.Implicits._
  import akka.http.impl.server.Util._

  /**
   * Creates a marshaller by specifying a media type and conversion function from `T` to String.
   * The charset for encoding the response will be negotiated with the client.
   */
  def toEntityString[T](mediaType: MediaType.WithOpenCharset, convert: function.Function[T, String]): Marshaller[T] =
    MarshallerImpl(_ ⇒ ScalaMarshaller.stringMarshaller(mediaType.asScala).compose[T](convert(_)))

  /**
   * Creates a marshaller by specifying a media type and conversion function from `T` to String.
   * The charset for encoding the response will be negotiated with the client.
   */
  def toEntityString[T](mediaType: MediaType.WithFixedCharset, convert: function.Function[T, String]): Marshaller[T] =
    MarshallerImpl(_ ⇒ ScalaMarshaller.stringMarshaller(mediaType.asScala).compose[T](convert(_)))

  /**
   * Creates a marshaller from a ContentType and a conversion function from `T` to a `Array[Byte]`.
   */
  def toEntityBytes[T](contentType: ContentType, convert: function.Function[T, Array[Byte]]): Marshaller[T] =
    toEntity(contentType, convert.andThen(scaladsl.model.HttpEntity(contentType.asScala, _)))

  /**
   * Creates a marshaller from a ContentType and a conversion function from `T` to a `ByteString`.
   */
  def toEntityByteString[T](contentType: ContentType, convert: function.Function[T, ByteString]): Marshaller[T] =
    toEntity(contentType, convert.andThen(scaladsl.model.HttpEntity(contentType.asScala, _)))

  /**
   * Creates a marshaller from a ContentType and a conversion function from `T` to a `ResponseEntity`.
   */
  def toEntity[T](contentType: ContentType, convert: function.Function[T, ResponseEntity]): Marshaller[T] =
    MarshallerImpl { _ ⇒
      ScalaMarshaller.withFixedContentType(contentType.asScala)(t ⇒
        HttpResponse.create().withStatus(200).withEntity(convert(t)).asScala)
    }

  /**
   * Creates a marshaller from a ContentType and a conversion function from `T` to an `HttpResponse`.
   */
  def toResponse[T](contentType: ContentType, convert: function.Function[T, HttpResponse]): Marshaller[T] =
    MarshallerImpl { _ ⇒
      ScalaMarshaller.withFixedContentType(contentType.asScala)(t ⇒ convert(t).asScala)
    }
}
