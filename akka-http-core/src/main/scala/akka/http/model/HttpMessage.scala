/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import scala.collection.immutable
import scala.annotation.tailrec
import scala.reflect.{ classTag, ClassTag }
import HttpCharsets._
import headers._

/**
 * Common base class of HttpRequest and HttpResponse.
 */
sealed abstract class HttpMessage {
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
  def withEntity(entity: HttpEntity): Self

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
  def header[T <: HttpHeader: ClassTag]: Option[T] = {
    val erasure = classTag[T].runtimeClass
    headers.find(erasure.isInstance).asInstanceOf[Option[T]]
  }

  /**
   * Returns true if this message is an
   * - HttpRequest and the client does not want to reuse the connection after the response for this request has been received
   * - HttpResponse and the server will close the connection after this response
   */
  def connectionCloseExpected: Boolean = HttpMessage.connectionCloseExpected(protocol, header[Connection])
}

object HttpMessage {
  private[http] def connectionCloseExpected(protocol: HttpProtocol, connectionHeader: Option[Connection]): Boolean =
    protocol match {
      case HttpProtocols.`HTTP/1.1` ⇒ connectionHeader.isDefined && connectionHeader.get.hasClose
      case HttpProtocols.`HTTP/1.0` ⇒ connectionHeader.isEmpty || !connectionHeader.get.hasKeepAlive
    }
}

/**
 * The immutable model of an HTTP request.
 */
case class HttpRequest(method: HttpMethod = HttpMethods.GET,
                       uri: Uri = Uri./,
                       headers: immutable.Seq[HttpHeader] = Nil,
                       entity: HttpEntity.Regular = HttpEntity.Empty,
                       protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends HttpMessage {
  require(!uri.isEmpty, "An HttpRequest must not have an empty Uri")

  type Self = HttpRequest

  def isRequest = true
  def isResponse = false

  /**
   * Returns a copy of this requests with the URI resolved according to the logic defined at
   * http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-5.5
   */
  def withEffectiveUri(securedConnection: Boolean, defaultHostHeader: Host = Host.empty): HttpRequest = {
    val hostHeader = header[Host]
    if (uri.isRelative) {
      def fail(detail: String) =
        sys.error(s"Cannot establish effective request URI of $this, request has a relative URI and $detail")
      val Host(host, port) = hostHeader match {
        case None                 ⇒ if (defaultHostHeader.isEmpty) fail("is missing a `Host` header") else defaultHostHeader
        case Some(x) if x.isEmpty ⇒ if (defaultHostHeader.isEmpty) fail("an empty `Host` header") else defaultHostHeader
        case Some(x)              ⇒ x
      }
      copy(uri = uri.toEffectiveHttpRequestUri(host, port, securedConnection))
    } else // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-22#section-5.4
    if (hostHeader.isEmpty || uri.authority.isEmpty && hostHeader.get.isEmpty ||
      hostHeader.get.host.equalsIgnoreCase(uri.authority.host)) this
    else sys.error("'Host' header value doesn't match request target authority")
  }

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
      case Nil ⇒ 1.0f // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.1
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
      case Nil ⇒ 1.0f // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.2
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
      case Nil ⇒ 1.0f // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.3
      case x   ⇒ x collectFirst { case r if r matches encoding ⇒ r.qValue } getOrElse 0f
    }

  /**
   * Determines whether one of the given content-types is accepted by the client.
   * If a given ContentType does not define a charset an accepted charset is selected, i.e. the method guarantees
   * that, if a ContentType instance is returned within the option, it will contain a defined charset.
   */
  def acceptableContentType(contentTypes: Seq[ContentType]): Option[ContentType] = {
    val mediaRanges = acceptedMediaRanges // cache for performance
    val charsetRanges = acceptedCharsetRanges // cache for performance

    @tailrec def findBest(ix: Int = 0, result: ContentType = null, maxQ: Float = 0f): Option[ContentType] =
      if (ix < contentTypes.size) {
        val ct = contentTypes(ix)
        val q = qValueForMediaType(ct.mediaType, mediaRanges)
        if (q > maxQ && (ct.noCharsetDefined || isCharsetAccepted(ct.charset, charsetRanges))) findBest(ix + 1, ct, q)
        else findBest(ix + 1, result, maxQ)
      } else Option(result)

    findBest() match {
      case x @ Some(ct) if ct.isCharsetDefined ⇒ x
      case Some(ct) ⇒
        // logic for choosing the charset adapted from http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.2
        def withCharset(cs: HttpCharset) = Some(ContentType(ct.mediaType, cs))
        if (qValueForCharset(`UTF-8`, charsetRanges) == 1f) withCharset(`UTF-8`)
        else charsetRanges match { // ranges are sorted by descending q-value
          case (HttpCharsetRange.One(cs, qValue)) :: _ ⇒
            if (qValue == 1f) withCharset(cs)
            else if (qValueForCharset(`ISO-8859-1`, charsetRanges) == 1f) withCharset(`ISO-8859-1`)
            else if (qValue > 0f) withCharset(cs)
            else None
          case _ ⇒ None
        }
      case None ⇒ None
    }
  }

  /**
   * Determines whether this request can be safely retried, which is the case only of the request method is idempotent.
   */
  def canBeRetried = method.isIdempotent

  def withHeaders(headers: immutable.Seq[HttpHeader]): HttpRequest =
    if (headers eq this.headers) this else copy(headers = headers)

  def withEntity(entity: HttpEntity): HttpRequest =
    if (entity ne this.entity) entity match {
      case x: HttpEntity.Regular ⇒ copy(entity = x)
      case _                     ⇒ throw new IllegalArgumentException("entity must be HttpEntity.Regular")
    }
    else this

  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: HttpEntity): HttpRequest =
    if ((headers ne this.headers) || (entity ne this.entity)) entity match {
      case x: HttpEntity.Regular ⇒ copy(headers = headers, entity = x)
      case _                     ⇒ throw new IllegalArgumentException("entity must be HttpEntity.Regular")
    }
    else this
}

/**
 * The immutable model of an HTTP response.
 */
case class HttpResponse(status: StatusCode = StatusCodes.OK,
                        headers: immutable.Seq[HttpHeader] = Nil,
                        entity: HttpEntity = HttpEntity.Empty,
                        protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends HttpMessage {
  type Self = HttpResponse

  def isRequest = false
  def isResponse = true

  def withHeaders(headers: immutable.Seq[HttpHeader]) =
    if (headers eq this.headers) this else copy(headers = headers)

  def withEntity(entity: HttpEntity) = if (entity eq this.entity) this else copy(entity = entity)

  def withHeadersAndEntity(headers: immutable.Seq[HttpHeader], entity: HttpEntity) =
    if ((headers eq this.headers) && (entity eq this.entity)) this else copy(headers = headers, entity = entity)
}