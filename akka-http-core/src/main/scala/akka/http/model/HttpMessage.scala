/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import scala.annotation.tailrec
import scala.reflect.{ classTag, ClassTag }
import akka.util.{ByteString, Bytes}

/** Any part of an HTTP message */
sealed trait HttpMessagePart

/** Any part of an HTTP request */
sealed trait HttpRequestPart extends HttpMessagePart
/** Any part of an HTTP response */
sealed trait HttpResponsePart extends HttpMessagePart

/** Any start part of an HTTP message */
sealed trait HttpMessageStart extends HttpMessagePart {
  def message: HttpMessage

  def mapHeaders(f: List[HttpHeader] ⇒ List[HttpHeader]): HttpMessageStart
}

object HttpMessageStart {
  def unapply(x: HttpMessageStart): Option[HttpMessage] = Some(x.message)
}

/** Any end part of an HTTP message */
sealed trait HttpMessageEnd extends HttpMessagePart

/** Common abstractions for a complete HTTP message */
sealed abstract class HttpMessage extends HttpMessageStart with HttpMessageEnd {
  type Self <: HttpMessage

  def message: Self
  def isRequest: Boolean
  def isResponse: Boolean

  def headers: List[HttpHeader]
  def entity: HttpEntity
  def protocol: HttpProtocol

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: HttpHeader*): Self = withHeaders(headers.toList)

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: List[HttpHeader]): Self

  /**
   * Returns a new message that contains all of the given default headers which didn't already
   * exist (by case-insensitive header name) in this message.
   */
  def withDefaultHeaders(defaultHeaders: List[HttpHeader]) = {
    @tailrec def patch(remaining: List[HttpHeader], result: List[HttpHeader] = headers): List[HttpHeader] =
      remaining match {
        case h :: rest if result.exists(_.is(h.lowercaseName)) ⇒ patch(rest, result)
        case h :: rest ⇒ patch(rest, h :: result)
        case Nil ⇒ result
      }
    withHeaders(patch(defaultHeaders))
  }

  /** Returns a copy of this message with the entity set to the given one. */
  def withEntity(entity: HttpEntity): Self

  /** Returns a copy of this message with the entity and headers set to the given ones. */
  def withHeadersAndEntity(headers: List[HttpHeader], entity: HttpEntity): Self

  /** Returns the start part for this message */
  def chunkedMessageStart: HttpMessageStart

  /** Returns a copy of this message with the list of headers transformed by the given function */
  def mapHeaders(f: List[HttpHeader] ⇒ List[HttpHeader]): Self = withHeaders(f(headers))
  /** Returns a copy of this message with the entity transformed by the given function */
  def mapEntity(f: HttpEntity ⇒ HttpEntity): Self = withEntity(f(entity))

  /** Returns the first header of the given type */
  def header[T <: HttpHeader: ClassTag]: Option[T] = {
    val erasure = classTag[T].runtimeClass
    @tailrec def next(headers: List[HttpHeader]): Option[T] =
      if (headers.isEmpty) None
      else if (erasure.isInstance(headers.head)) Some(headers.head.asInstanceOf[T]) else next(headers.tail)
    next(headers)
  }

  // FIXME: resurrect the following methods once the model was ported:
  // encoding, connectionCloseExpected, asPartStream
}

/**
 * A complete HTTP request.
 */
case class HttpRequest(method: HttpMethod = HttpMethods.GET,
                       uri: Uri = Uri./,
                       headers: List[HttpHeader] = Nil,
                       entity: HttpEntity = HttpEntity.Empty,
                       protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends HttpMessage with HttpRequestPart {
  require(!uri.isEmpty, "An HttpRequest must not have an empty Uri")

  type Self = HttpRequest
  def message = this
  def isRequest = true
  def isResponse = false

  // FIXME: resurrect the following methods:
  // withEffectiveUri, acceptedMediaRanges, acceptedCharsetRanges, acceptedEncodingRanges, cookies
  // isMediaTypeAccepted, qValueForMediaType, isCharsetAccepted, qValueForCharset, isEncodingAccepted
  // qValueForEncoding
  // acceptableContentType probably has to be rewritten when the old httpx is migrated

  def canBeRetried = method.isIdempotent
  def withHeaders(headers: List[HttpHeader]) = if (headers eq this.headers) this else copy(headers = headers)
  def withEntity(entity: HttpEntity) = if (entity eq this.entity) this else copy(entity = entity)
  def withHeadersAndEntity(headers: List[HttpHeader], entity: HttpEntity) =
    if ((headers eq this.headers) && (entity eq this.entity)) this else copy(headers = headers, entity = entity)

  def chunkedMessageStart: ChunkedRequestStart = ChunkedRequestStart(this)
}

/**
 * A complete HTTP response.
 */
case class HttpResponse(status: StatusCode = StatusCodes.OK,
                        entity: HttpEntity = HttpEntity.Empty,
                        headers: List[HttpHeader] = Nil,
                        protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends HttpMessage with HttpResponsePart {
  type Self = HttpResponse

  def message = this
  def isRequest = false
  def isResponse = true

  def withHeaders(headers: List[HttpHeader]) = if (headers eq this.headers) this else copy(headers = headers)
  def withEntity(entity: HttpEntity) = if (entity eq this.entity) this else copy(entity = entity)
  def withHeadersAndEntity(headers: List[HttpHeader], entity: HttpEntity) =
    if ((headers eq this.headers) && (entity eq this.entity)) this else copy(headers = headers, entity = entity)

  def chunkedMessageStart: ChunkedResponseStart = ChunkedResponseStart(this)
}

/**
 * Individual chunks of a chunked HTTP message (request or response).
 */
case class MessageChunk(data: Bytes, extension: String) extends HttpRequestPart with HttpResponsePart {
  require(data.nonEmpty, "Cannot create MessageChunk with empty data")
}

object MessageChunk {
  import HttpCharsets._
  // TODO: if these constructors are the same or sufficiently similar to the HttpData.NonEmpty ones
  // we should think about providing implicit conversions (magnet pattern like) instead of what's here
  def apply(body: String): MessageChunk =
    apply(body, "")
  def apply(body: String, charset: HttpCharset): MessageChunk =
    apply(body, charset, "")
  def apply(body: String, extension: String): MessageChunk =
    apply(body, `UTF-8`, extension)
  def apply(body: String, charset: HttpCharset, extension: String): MessageChunk =
    apply(ByteString(body.getBytes(charset.nioCharset)), extension)
  def apply(bytes: Array[Byte]): MessageChunk =
    apply(ByteString(bytes))
  def apply(data: Bytes): MessageChunk =
    apply(data, "")
}

/**
 * The start of a chunked request. As it may contain all of what a complete request contains
 * it wraps an HttpRequest instance. However, the entity in the request will only be the start of the entity.
 * Remaining data will be received as MessageChunks.
 */
case class ChunkedRequestStart(request: HttpRequest) extends HttpMessageStart with HttpRequestPart {
  def message = request

  def mapHeaders(f: List[HttpHeader] ⇒ List[HttpHeader]): ChunkedRequestStart =
    ChunkedRequestStart(request mapHeaders f)
}

/**
 * The start of a chunked response. As it may contain all of what a complete response contains
 * it wraps an HttpResponse instance. However, the entity in the response will only be the start of the entity.
 * Remaining data will be received as MessageChunks.
 */
case class ChunkedResponseStart(response: HttpResponse) extends HttpMessageStart with HttpResponsePart {
  def message = response

  def mapHeaders(f: List[HttpHeader] ⇒ List[HttpHeader]): ChunkedResponseStart =
    ChunkedResponseStart(response mapHeaders f)
}

/**
 * The last part of any chunked request or response. May contain extensions or trailer headers.
 */
case class ChunkedMessageEnd(extension: String = "",
                             trailer: List[HttpHeader] = Nil) extends HttpRequestPart with HttpResponsePart with HttpMessageEnd {
  if (!trailer.isEmpty) {
    require(trailer.forall(_.isNot("content-length")), "Content-Length header is not allowed in trailer")
    require(trailer.forall(_.isNot("transfer-encoding")), "Transfer-Encoding header is not allowed in trailer")
    require(trailer.forall(_.isNot("trailer")), "Trailer header is not allowed in trailer")
  }
}

/**
 * The most common ChunkedMessageEnd that neither contains extensions nor trailer headers.
 */
object ChunkedMessageEnd extends ChunkedMessageEnd("", Nil)
