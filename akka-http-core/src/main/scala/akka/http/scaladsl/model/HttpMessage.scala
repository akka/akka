/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.lang.{ Iterable ⇒ JIterable }
import java.util.Optional

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, ExecutionContext }
import scala.collection.immutable
import scala.reflect.{ classTag, ClassTag }
import akka.parboiled2.CharUtils
import akka.stream.Materializer
import akka.util.ByteString
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.util.FastFuture._
import headers._

import scala.compat.java8.OptionConverters._

/**
 * Common base class of HttpRequest and HttpResponse.
 */
sealed trait HttpMessage extends jm.HttpMessage {
  type Self <: HttpMessage
  def self: Self

  def isRequest: Boolean
  def isResponse: Boolean

  def headers: immutable.Seq[HttpHeader]
  def entity: ResponseEntity
  def protocol: HttpProtocol

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: HttpHeader*): Self = withHeaders(headers.toList)

  /** Returns a copy of this message with the list of headers set to the given ones. */
  def withHeaders(headers: immutable.Seq[HttpHeader]): Self

  /**
   * Returns a new message that contains all of the given default headers which didn't already
   * exist (by case-insensitive header name) in this message.
   */
  def withDefaultHeaders(defaultHeaders: HttpHeader*): Self = withDefaultHeaders(defaultHeaders.toList)

  /**
   * Returns a new message that contains all of the given default headers which didn't already
   * exist (by case-insensitive header name) in this message.
   */
  def withDefaultHeaders(defaultHeaders: immutable.Seq[HttpHeader]): Self =
    withHeaders {
      if (headers.isEmpty) defaultHeaders
      else defaultHeaders.foldLeft(headers) { (acc, h) ⇒ if (headers.exists(_ is h.lowercaseName)) acc else h +: acc }
    }

  /** Returns a copy of this message with the entity set to the given one. */
  def withEntity(entity: MessageEntity): Self

  /** Returns a sharable and serializable copy of this message with a strict entity. */
  def toStrict(timeout: FiniteDuration)(implicit ec: ExecutionContext, fm: Materializer): Future[Self] =
    entity.toStrict(timeout).fast.map(this.withEntity)

  /** Returns a copy of this message with the entity and headers set to the given ones. */
  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: MessageEntity): Self

  /** Returns a copy of this message with the list of headers transformed by the given function */
  def mapHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): Self = withHeaders(f(headers))

  /**
   * The content encoding as specified by the Content-Encoding header. If no Content-Encoding header is present the
   * default value 'identity' is returned.
   */
  def encoding: HttpEncoding = header[`Content-Encoding`] match {
    case Some(x) ⇒ x.encodings.head
    case None    ⇒ HttpEncodings.identity
  }

  /** Returns the first header of the given type if there is one */
  def header[T <: jm.HttpHeader: ClassTag]: Option[T] = {
    val erasure = classTag[T].runtimeClass
    headers.find(erasure.isInstance).asInstanceOf[Option[T]]
  }

  /**
   * Returns true if this message is an:
   *  - HttpRequest and the client does not want to reuse the connection after the response for this request has been received
   *  - HttpResponse and the server will close the connection after this response
   */
  def connectionCloseExpected: Boolean = HttpMessage.connectionCloseExpected(protocol, header[Connection])

  def addHeader(header: jm.HttpHeader): Self = mapHeaders(_ :+ header.asInstanceOf[HttpHeader])

  /** Removes the header with the given name (case-insensitive) */
  def removeHeader(headerName: String): Self = {
    val lowerHeaderName = headerName.toRootLowerCase
    mapHeaders(_.filterNot(_.is(lowerHeaderName)))
  }

  def withEntity(string: String): Self = withEntity(HttpEntity(string))
  def withEntity(bytes: Array[Byte]): Self = withEntity(HttpEntity(bytes))
  def withEntity(bytes: ByteString): Self = withEntity(HttpEntity(bytes))
  def withEntity(contentType: jm.ContentType.NonBinary, string: String): Self =
    withEntity(HttpEntity(contentType.asInstanceOf[ContentType.NonBinary], string))
  def withEntity(contentType: jm.ContentType, bytes: Array[Byte]): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], bytes))
  def withEntity(contentType: jm.ContentType, bytes: ByteString): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], bytes))
  def withEntity(contentType: jm.ContentType, file: java.io.File): Self = withEntity(HttpEntity(contentType.asInstanceOf[ContentType], file))

  import collection.JavaConverters._
  /** Java API */
  def getHeaders: JIterable[jm.HttpHeader] = (headers: immutable.Seq[jm.HttpHeader]).asJava
  /** Java API */
  def getHeader[T <: jm.HttpHeader](headerClass: Class[T]): Optional[T] = header(ClassTag(headerClass)).asJava
  /** Java API */
  def getHeader(headerName: String): Optional[jm.HttpHeader] = {
    val lowerCased = headerName.toRootLowerCase
    Util.convertOption(headers.find(_.is(lowerCased))) // Upcast because of invariance
  }
  /** Java API */
  def addHeaders(headers: JIterable[jm.HttpHeader]): Self = mapHeaders(_ ++ headers.asScala.asInstanceOf[Iterable[HttpHeader]])
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
                             entity: RequestEntity = HttpEntity.Empty,
                             protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends jm.HttpRequest with HttpMessage {
  HttpRequest.verifyUri(uri)
  require(entity.isKnownEmpty || method.isEntityAccepted, s"Requests with method '${method.value}' must have an empty entity")
  require(protocol != HttpProtocols.`HTTP/1.0` || !entity.isInstanceOf[HttpEntity.Chunked],
    "HTTP/1.0 requests must not have a chunked entity")

  type Self = HttpRequest
  def self = this

  override def isRequest = true
  override def isResponse = false

  /**
   * Resolve this request's URI according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   *
   * Throws an [[IllegalUriException]] if the URI is relative and the `headers` don't
   * include a valid [[akka.http.scaladsl.model.headers.Host]] header or if URI authority and [[akka.http.scaladsl.model.headers.Host]] header don't match.
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
   * All cookies provided by the client in one or more `Cookie` headers.
   */
  def cookies: immutable.Seq[HttpCookiePair] = for (`Cookie`(cookies) ← headers; cookie ← cookies) yield cookie

  /**
   * Determines whether this request can be safely retried, which is the case only of the request method is idempotent.
   */
  def canBeRetried = method.isIdempotent

  override def withHeaders(headers: immutable.Seq[HttpHeader]): HttpRequest =
    if (headers eq this.headers) this else copy(headers = headers)

  override def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: RequestEntity): HttpRequest = copy(headers = headers, entity = entity)
  override def withEntity(entity: jm.RequestEntity): HttpRequest = copy(entity = entity.asInstanceOf[RequestEntity])
  override def withEntity(entity: MessageEntity): HttpRequest = copy(entity = entity)

  def mapEntity(f: RequestEntity ⇒ RequestEntity): HttpRequest = withEntity(f(entity))

  override def withMethod(method: akka.http.javadsl.model.HttpMethod): HttpRequest = copy(method = method.asInstanceOf[HttpMethod])
  override def withProtocol(protocol: akka.http.javadsl.model.HttpProtocol): HttpRequest = copy(protocol = protocol.asInstanceOf[HttpProtocol])
  override def withUri(path: String): HttpRequest = withUri(Uri(path))
  def withUri(uri: Uri): HttpRequest = copy(uri = uri)

  import JavaMapping.Implicits._
  /** Java API */
  override def getUri: jm.Uri = uri.asJava
  /** Java API */
  override def withUri(uri: jm.Uri): HttpRequest = copy(uri = uri.asScala)
}

object HttpRequest {
  /**
   * Determines the effective request URI according to the logic defined at
   * http://tools.ietf.org/html/rfc7230#section-5.5
   *
   * Throws an [[IllegalUriException]] if the URI is relative and the `headers` don't
   * include a valid [[Host]] header or if URI authority and [[Host]] header don't match.
   */
  def effectiveUri(uri: Uri, headers: immutable.Seq[HttpHeader], securedConnection: Boolean, defaultHostHeader: Host): Uri = {
    val hostHeader = headers.collectFirst { case x: Host ⇒ x }
    if (uri.isRelative) {
      def fail(detail: String) =
        throw IllegalUriException(s"Cannot establish effective URI of request to `$uri`, request has a relative URI and $detail")
      val Host(host, port) = hostHeader match {
        case None                 ⇒ if (defaultHostHeader.isEmpty) fail("is missing a `Host` header") else defaultHostHeader
        case Some(x) if x.isEmpty ⇒ if (defaultHostHeader.isEmpty) fail("an empty `Host` header") else defaultHostHeader
        case Some(x)              ⇒ x
      }
      uri.toEffectiveHttpRequestUri(host, port, securedConnection)
    } else // http://tools.ietf.org/html/rfc7230#section-5.4
    if (hostHeader.isEmpty || uri.authority.isEmpty && hostHeader.get.isEmpty ||
      hostHeader.get.host.equalsIgnoreCase(uri.authority.host) && hostHeader.get.port == uri.authority.port) uri
    else throw IllegalUriException(s"'Host' header value of request to `$uri` doesn't match request target authority",
      s"Host header: $hostHeader\nrequest target authority: ${uri.authority}")
  }

  /**
   * Verifies that the given [[Uri]] is non-empty and has either scheme `http`, `https` or no scheme at all.
   * If any of these conditions is not met the method throws an [[IllegalArgumentException]].
   */
  def verifyUri(uri: Uri): Unit =
    if (uri.isEmpty) throw new IllegalArgumentException("`uri` must not be empty")
    else {
      def c(i: Int) = CharUtils.toLowerCase(uri.scheme charAt i)
      uri.scheme.length match {
        case 0 ⇒ // ok
        case 4 if c(0) == 'h' && c(1) == 't' && c(2) == 't' && c(3) == 'p' ⇒ // ok
        case 5 if c(0) == 'h' && c(1) == 't' && c(2) == 't' && c(3) == 'p' && c(4) == 's' ⇒ // ok
        case _ ⇒ throw new IllegalArgumentException("""`uri` must have scheme "http", "https" or no scheme""")
      }
    }
}

/**
 * The immutable HTTP response model.
 */
final case class HttpResponse(status: StatusCode = StatusCodes.OK,
                              headers: immutable.Seq[HttpHeader] = Nil,
                              entity: ResponseEntity = HttpEntity.Empty,
                              protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends jm.HttpResponse with HttpMessage {
  require(entity.isKnownEmpty || status.allowsEntity, "Responses with this status code must have an empty entity")
  require(protocol == HttpProtocols.`HTTP/1.1` || !entity.isInstanceOf[HttpEntity.Chunked],
    "HTTP/1.0 responses must not have a chunked entity")

  type Self = HttpResponse
  def self = this

  override def isRequest = false
  override def isResponse = true

  override def withHeaders(headers: immutable.Seq[HttpHeader]) =
    if (headers eq this.headers) this else copy(headers = headers)

  override def withProtocol(protocol: akka.http.javadsl.model.HttpProtocol): akka.http.javadsl.model.HttpResponse = copy(protocol = protocol.asInstanceOf[HttpProtocol])
  override def withStatus(statusCode: Int): akka.http.javadsl.model.HttpResponse = copy(status = statusCode)
  override def withStatus(statusCode: akka.http.javadsl.model.StatusCode): akka.http.javadsl.model.HttpResponse = copy(status = statusCode.asInstanceOf[StatusCode])

  override def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: MessageEntity): HttpResponse = withHeadersAndEntity(headers, entity: ResponseEntity)
  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: ResponseEntity): HttpResponse = copy(headers = headers, entity = entity)
  override def withEntity(entity: jm.ResponseEntity): HttpResponse = copy(entity = entity.asInstanceOf[ResponseEntity])
  override def withEntity(entity: MessageEntity): HttpResponse = copy(entity = entity)
  override def withEntity(entity: jm.RequestEntity): HttpResponse = withEntity(entity: jm.ResponseEntity)

  def mapEntity(f: ResponseEntity ⇒ ResponseEntity): HttpResponse = withEntity(f(entity))
}