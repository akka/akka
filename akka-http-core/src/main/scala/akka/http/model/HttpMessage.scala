/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.lang.{ Iterable ⇒ JIterable }
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import scala.collection.immutable
import scala.reflect.{ classTag, ClassTag }
import akka.stream.FlowMaterializer
import akka.util.ByteString
import akka.http.util._
import headers._
import HttpCharsets._

/**
 * Common base class of HttpRequest and HttpResponse.
 */
sealed trait HttpMessage extends japi.HttpMessage {
  type Self <: HttpMessage

  def isRequest: Boolean
  def isResponse: Boolean

  def headers: immutable.Seq[HttpHeader]
  def entity: HttpEntity
  def protocol: HttpProtocol

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: HttpHeader*): Self = withHeaders(immutable.Seq(headers: _*))

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: immutable.Seq[HttpHeader]): Self

  /**
   * Returns a new message that contains all of the given default headers which didn't already
   * exist (by case-insensitive header name) in this message.
   */
  def withDefaultHeaders(defaultHeaders: immutable.Seq[HttpHeader]) =
    withHeaders {
      defaultHeaders.foldLeft(headers) { (acc, h) ⇒ if (acc.exists(_ is h.lowercaseName)) acc else acc :+ h }
    }

  /** Returns a copy of this message with the entity set to the given one. */
  def withEntity(entity: japi.HttpEntity): Self

  /** Returns a sharable and serializable copy of this message with a strict entity. */
  def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: FlowMaterializer): Deferrable[Self] =
    entity.toStrict(timeout).map(this.withEntity)

  /** Returns a copy of this message with the entity and headers set to the given ones. */
  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: HttpEntity): Self

  /** Returns a copy of this message with the list of headers transformed by the given function */
  def mapHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): Self = withHeaders(f(headers))

  /** Returns a copy of this message with the entity transformed by the given function */
  def mapEntity(f: HttpEntity ⇒ HttpEntity): Self = withEntity(f(entity))

  /**
   * The content encoding as specified by the Content-Encoding header. If no Content-Encoding header is present the
   * default value 'identity' is returned.
   */
  def encoding: HttpEncoding = header[`Content-Encoding`] match {
    case Some(x) ⇒ x.encodings.head
    case None    ⇒ HttpEncodings.identity
  }

  /** Returns the first header of the given type if there is one */
  def header[T <: japi.HttpHeader: ClassTag]: Option[T] = {
    val erasure = classTag[T].runtimeClass
    headers.find(erasure.isInstance).asInstanceOf[Option[T]]
  }

  /**
   * Returns true if this message is an:
   *  - HttpRequest and the client does not want to reuse the connection after the response for this request has been received
   *  - HttpResponse and the server will close the connection after this response
   */
  def connectionCloseExpected: Boolean = HttpMessage.connectionCloseExpected(protocol, header[Connection])

  def addHeader(header: japi.HttpHeader): Self = mapHeaders(_ :+ header.asInstanceOf[HttpHeader])

  /** Removes the header with the given name (case-insensitive) */
  def removeHeader(headerName: String): Self = {
    val lowerHeaderName = headerName.toRootLowerCase
    mapHeaders(_.filterNot(_.is(lowerHeaderName)))
  }

  def withEntity(string: String): Self = withEntity(HttpEntity(string))
  def withEntity(bytes: Array[Byte]): Self = withEntity(HttpEntity(bytes))
  def withEntity(bytes: ByteString): Self = withEntity(HttpEntity(bytes))
  def withEntity(contentType: japi.ContentType, string: String): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], string))
  def withEntity(contentType: japi.ContentType, bytes: Array[Byte]): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], bytes))
  def withEntity(contentType: japi.ContentType, bytes: ByteString): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], bytes))
  def withEntity(contentType: japi.ContentType, file: java.io.File): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], file))

  import collection.JavaConverters._
  /** Java API */
  def getHeaders: JIterable[japi.HttpHeader] = (headers: immutable.Seq[japi.HttpHeader]).asJava
  /** Java API */
  def getHeader[T <: japi.HttpHeader](headerClass: Class[T]): akka.japi.Option[T] = header(ClassTag(headerClass))
  /** Java API */
  def getHeader(headerName: String): akka.japi.Option[japi.HttpHeader] = {
    val lowerCased = headerName.toRootLowerCase
    headers.find(_.is(lowerCased))
  }
  /** Java API */
  def addHeaders(headers: JIterable[japi.HttpHeader]): Self = mapHeaders(_ ++ headers.asScala.asInstanceOf[Iterable[HttpHeader]])
}

object HttpMessage {
  private[http] def connectionCloseExpected(protocol: HttpProtocol, connectionHeader: Option[Connection]): Boolean =
    protocol match {
      case HttpProtocols.`HTTP/1.1` ⇒ connectionHeader.isDefined && connectionHeader.get.hasClose
      case HttpProtocols.`HTTP/1.0` ⇒ connectionHeader.isEmpty || !connectionHeader.get.hasKeepAlive
    }
}

/**
 * The immutable model HTTP request model.
 */
final case class HttpRequest(method: HttpMethod = HttpMethods.GET,
                             uri: Uri = Uri./,
                             headers: immutable.Seq[HttpHeader] = Nil,
                             entity: HttpEntity.Regular = HttpEntity.Empty,
                             protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends japi.HttpRequest with HttpMessage {
  require(!uri.isEmpty, "An HttpRequest must not have an empty Uri")
  require(entity.isKnownEmpty || method.isEntityAccepted, "Requests with this method must have an empty entity")
  require(protocol == HttpProtocols.`HTTP/1.1` || !entity.isInstanceOf[HttpEntity.Chunked],
    "HTTP/1.0 requests must not have a chunked entity")

  type Self = HttpRequest

  override def isRequest = true
  override def isResponse = false

  /**
   * Resolve this request's URI according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   */
  def effectiveUri(securedConnection: Boolean, defaultHostHeader: Host = Host.empty): Uri =
    HttpRequest.effectiveUri(uri, headers, securedConnection, defaultHostHeader)

  /**
   * Returns a copy of this request with the URI resolved according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   */
  def withEffectiveUri(securedConnection: Boolean, defaultHostHeader: Host = Host.empty): HttpRequest =
    copy(uri = effectiveUri(securedConnection, defaultHostHeader))

  /**
   * The media-ranges accepted by the client according to the `Accept` request header.
   * The returned ranges are sorted by decreasing q-value.
   */
  def acceptedMediaRanges: immutable.Seq[MediaRange] =
    (for {
      Accept(mediaRanges) ← headers
      range ← mediaRanges
    } yield range).sortBy(-_.qValue)

  /**
   * The charset-ranges accepted by the client according to the `Accept-Charset` request header.
   * The returned ranges are sorted by decreasing q-value.
   */
  def acceptedCharsetRanges: immutable.Seq[HttpCharsetRange] =
    (for {
      `Accept-Charset`(charsetRanges) ← headers
      range ← charsetRanges
    } yield range).sortBy(-_.qValue)

  /**
   * The encoding-ranges accepted by the client according to the `Accept-Encoding` request header.
   * The returned ranges are sorted by decreasing q-value.
   */
  def acceptedEncodingRanges: immutable.Seq[HttpEncodingRange] =
    (for {
      `Accept-Encoding`(encodingRanges) ← headers
      range ← encodingRanges
    } yield range).sortBy(-_.qValue)

  /**
   * All cookies provided by the client in one or more `Cookie` headers.
   */
  def cookies: immutable.Seq[HttpCookie] = for (`Cookie`(cookies) ← headers; cookie ← cookies) yield cookie

  /**
   * Determines whether the given media-type is accepted by the client.
   */
  def isMediaTypeAccepted(mediaType: MediaType, ranges: Seq[MediaRange] = acceptedMediaRanges): Boolean =
    qValueForMediaType(mediaType, ranges) > 0f

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given media-type.
   */
  def qValueForMediaType(mediaType: MediaType, ranges: Seq[MediaRange] = acceptedMediaRanges): Float =
    ranges match {
      case Nil ⇒ 1.0f // http://tools.ietf.org/html/rfc7231#section-5.3.1
      case x   ⇒ x collectFirst { case r if r matches mediaType ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether the given charset is accepted by the client.
   */
  def isCharsetAccepted(charset: HttpCharset, ranges: Seq[HttpCharsetRange] = acceptedCharsetRanges): Boolean =
    qValueForCharset(charset, ranges) > 0f

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given charset.
   */
  def qValueForCharset(charset: HttpCharset, ranges: Seq[HttpCharsetRange] = acceptedCharsetRanges): Float =
    ranges match {
      case Nil ⇒ 1.0f // http://tools.ietf.org/html/rfc7231#section-5.3.1
      case x   ⇒ x collectFirst { case r if r matches charset ⇒ r.qValue } getOrElse (if (charset == `ISO-8859-1`) 1f else 0f)
    }

  /**
   * Determines whether the given encoding is accepted by the client.
   */
  def isEncodingAccepted(encoding: HttpEncoding, ranges: Seq[HttpEncodingRange] = acceptedEncodingRanges): Boolean =
    qValueForEncoding(encoding, ranges) > 0f

  /**
   * Returns the q-value that the client (implicitly or explicitly) attaches to the given encoding.
   */
  def qValueForEncoding(encoding: HttpEncoding, ranges: Seq[HttpEncodingRange] = acceptedEncodingRanges): Float =
    ranges match {
      case Nil ⇒ 1.0f // http://tools.ietf.org/html/rfc7231#section-5.3.1
      case x   ⇒ x collectFirst { case r if r matches encoding ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether this request can be safely retried, which is the case only of the request method is idempotent.
   */
  def canBeRetried = method.isIdempotent

  override def withHeaders(headers: immutable.Seq[HttpHeader]): HttpRequest =
    if (headers eq this.headers) this else copy(headers = headers)

  override def withEntity(entity: japi.HttpEntity): HttpRequest =
    if (entity ne this.entity) entity match {
      case x: HttpEntity.Regular ⇒ copy(entity = x)
      case _                     ⇒ throw new IllegalArgumentException("entity must be HttpEntity.Regular")
    }
    else this

  override def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: HttpEntity): HttpRequest =
    if ((headers ne this.headers) || (entity ne this.entity)) entity match {
      case x: HttpEntity.Regular ⇒ copy(headers = headers, entity = x)
      case _                     ⇒ throw new IllegalArgumentException("entity must be HttpEntity.Regular")
    }
    else this

  override def withMethod(method: akka.http.model.japi.HttpMethod): HttpRequest = copy(method = method.asInstanceOf[HttpMethod])
  override def withProtocol(protocol: akka.http.model.japi.HttpProtocol): HttpRequest = copy(protocol = protocol.asInstanceOf[HttpProtocol])
  override def withUri(path: String): HttpRequest = copy(uri = Uri(path))

  /** Java API */
  override def getUri: japi.Uri = japi.Accessors.Uri(uri)
  /** Java API */
  override def withUri(relativeUri: akka.http.model.japi.Uri): HttpRequest = copy(uri = relativeUri.asInstanceOf[japi.JavaUri].uri)
}

object HttpRequest {
  /**
   * Determines the effective request URI according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   */
  def effectiveUri(uri: Uri, headers: immutable.Seq[HttpHeader], securedConnection: Boolean, defaultHostHeader: Host): Uri = {
    val hostHeader = headers.collectFirst { case x: Host ⇒ x }
    if (uri.isRelative) {
      def fail(detail: String) =
        throw new IllegalUriException(s"Cannot establish effective URI of request to `$uri`, request has a relative URI and $detail")
      val Host(host, port) = hostHeader match {
        case None                 ⇒ if (defaultHostHeader.isEmpty) fail("is missing a `Host` header") else defaultHostHeader
        case Some(x) if x.isEmpty ⇒ if (defaultHostHeader.isEmpty) fail("an empty `Host` header") else defaultHostHeader
        case Some(x)              ⇒ x
      }
      uri.toEffectiveHttpRequestUri(host, port, securedConnection)
    } else // http://tools.ietf.org/html/rfc7230#section-5.4
    if (hostHeader.isEmpty || uri.authority.isEmpty && hostHeader.get.isEmpty ||
      hostHeader.get.host.equalsIgnoreCase(uri.authority.host)) uri
    else throw new IllegalUriException("'Host' header value of request to `$uri` doesn't match request target authority",
      s"Host header: $hostHeader\nrequest target authority: ${uri.authority}")
  }
}

/**
 * The immutable HTTP response model.
 */
final case class HttpResponse(status: StatusCode = StatusCodes.OK,
                              headers: immutable.Seq[HttpHeader] = Nil,
                              entity: HttpEntity = HttpEntity.Empty,
                              protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends japi.HttpResponse with HttpMessage {
  type Self = HttpResponse

  override def isRequest = false
  override def isResponse = true

  override def withHeaders(headers: immutable.Seq[HttpHeader]) =
    if (headers eq this.headers) this else copy(headers = headers)

  override def withEntity(entity: japi.HttpEntity) = if (entity eq this.entity) this else copy(entity = entity.asInstanceOf[HttpEntity])

  override def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: HttpEntity) =
    if ((headers eq this.headers) && (entity eq this.entity)) this else copy(headers = headers, entity = entity)

  override def withProtocol(protocol: akka.http.model.japi.HttpProtocol): akka.http.model.japi.HttpResponse = copy(protocol = protocol.asInstanceOf[HttpProtocol])
  override def withStatus(statusCode: Int): akka.http.model.japi.HttpResponse = copy(status = statusCode)
  override def withStatus(statusCode: akka.http.model.japi.StatusCode): akka.http.model.japi.HttpResponse = copy(status = statusCode.asInstanceOf[StatusCode])
}