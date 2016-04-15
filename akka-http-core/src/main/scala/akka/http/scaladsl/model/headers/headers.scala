/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import java.lang.Iterable
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util
import javax.net.ssl.SSLSession
import akka.stream.scaladsl.ScalaSessionAPI

import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }
import scala.annotation.tailrec
import scala.collection.immutable
import akka.parboiled2.util.Base64
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.model._

sealed abstract class ModeledCompanion[T: ClassTag] extends Renderable {
  val name = getClass.getSimpleName.replace("$minus", "-").dropRight(1) // trailing $
  val lowercaseName = name.toRootLowerCase
  private[this] val nameBytes = name.asciiBytes
  final def render[R <: Rendering](r: R): r.type = r ~~ nameBytes ~~ ':' ~~ ' '

  /**
   * Parses the given value into a header of this type. Returns `Right[T]` if parsing
   * was successful and `Left(errors)` otherwise.
   */
  def parseFromValueString(value: String): Either[List[ErrorInfo], T] =
    HttpHeader.parse(name, value) match {
      case HttpHeader.ParsingResult.Ok(header: T, Nil) ⇒ Right(header)
      case res                                         ⇒ Left(res.errors)
    }
}

sealed trait ModeledHeader extends HttpHeader with Serializable {
  def renderInRequests: Boolean = false // default implementation
  def renderInResponses: Boolean = false // default implementation
  def name: String = companion.name
  def value: String = renderValue(new StringRendering).get
  def lowercaseName: String = companion.lowercaseName
  final def render[R <: Rendering](r: R): r.type = renderValue(r ~~ companion)
  protected[http] def renderValue[R <: Rendering](r: R): r.type
  protected def companion: ModeledCompanion[_]
}

private[headers] sealed trait RequestHeader extends ModeledHeader { override def renderInRequests = true }
private[headers] sealed trait ResponseHeader extends ModeledHeader { override def renderInResponses = true }
private[headers] sealed trait RequestResponseHeader extends RequestHeader with ResponseHeader
private[headers] sealed trait SyntheticHeader extends ModeledHeader

/**
 * Superclass for user-defined custom headers defined by implementing `name` and `value`.
 *
 * Prefer to extend [[ModeledCustomHeader]] and [[ModeledCustomHeaderCompanion]] instead if
 * planning to use the defined header in match clauses (e.g. in the routing layer of Akka HTTP),
 * as they allow the custom header to be matched from [[RawHeader]] and vice-versa.
 */
abstract class CustomHeader extends jm.headers.CustomHeader {
  def lowercaseName: String = name.toRootLowerCase
  final def render[R <: Rendering](r: R): r.type = r ~~ name ~~ ':' ~~ ' ' ~~ value
}

/**
 * To be extended by companion object of a custom header extending [[ModeledCustomHeader]].
 * Implements necessary apply and unapply methods to make the such defined header feel "native".
 */
abstract class ModeledCustomHeaderCompanion[H <: ModeledCustomHeader[H]] {
  def name: String
  def lowercaseName: String = name.toRootLowerCase

  def parse(value: String): Try[H]

  def apply(value: String): H =
    parse(value) match {
      case Success(parsed) ⇒ parsed
      case Failure(ex)     ⇒ throw new IllegalArgumentException(s"Unable to construct custom header by parsing: '$value'", ex)
    }

  def unapply(h: HttpHeader): Option[String] = h match {
    case _: RawHeader    ⇒ if (h.lowercaseName == lowercaseName) Some(h.value) else None
    case _: CustomHeader ⇒ if (h.lowercaseName == lowercaseName) Some(h.value) else None
    case _               ⇒ None
  }

  final implicit val implicitlyLocatableCompanion: ModeledCustomHeaderCompanion[H] = this
}

/**
 * Support class for building user-defined custom headers defined by implementing `name` and `value`.
 * By implementing a [[ModeledCustomHeader]] instead of [[CustomHeader]] directly, all needed unapply
 * methods are provided for this class, such that it can be pattern matched on from [[RawHeader]] and
 * the other way around as well.
 */
abstract class ModeledCustomHeader[H <: ModeledCustomHeader[H]] extends CustomHeader { this: H ⇒
  def companion: ModeledCustomHeaderCompanion[H]

  final override def name = companion.name
  final override def lowercaseName = name.toRootLowerCase
}

import akka.http.impl.util.JavaMapping.Implicits._

// http://tools.ietf.org/html/rfc7231#section-5.3.2
object Accept extends ModeledCompanion[Accept] {
  def apply(mediaRanges: MediaRange*): Accept = apply(immutable.Seq(mediaRanges: _*))
  implicit val mediaRangesRenderer = Renderer.defaultSeqRenderer[MediaRange] // cache
}
final case class Accept(mediaRanges: immutable.Seq[MediaRange]) extends jm.headers.Accept with RequestHeader {
  import Accept.mediaRangesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ mediaRanges
  protected def companion = Accept
  def acceptsAll = mediaRanges.exists(mr ⇒ mr.isWildcard && mr.qValue > 0f)

  /** Java API */
  def getMediaRanges: Iterable[jm.MediaRange] = mediaRanges.asJava
}

// http://tools.ietf.org/html/rfc7231#section-5.3.3
object `Accept-Charset` extends ModeledCompanion[`Accept-Charset`] {
  def apply(first: HttpCharsetRange, more: HttpCharsetRange*): `Accept-Charset` = apply(immutable.Seq(first +: more: _*))
  implicit val charsetRangesRenderer = Renderer.defaultSeqRenderer[HttpCharsetRange] // cache
}
final case class `Accept-Charset`(charsetRanges: immutable.Seq[HttpCharsetRange]) extends jm.headers.AcceptCharset
  with RequestHeader {
  require(charsetRanges.nonEmpty, "charsetRanges must not be empty")
  import `Accept-Charset`.charsetRangesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ charsetRanges
  protected def companion = `Accept-Charset`

  /** Java API */
  def getCharsetRanges: Iterable[jm.HttpCharsetRange] = charsetRanges.asJava
}

// http://tools.ietf.org/html/rfc7231#section-5.3.4
object `Accept-Encoding` extends ModeledCompanion[`Accept-Encoding`] {
  def apply(encodings: HttpEncodingRange*): `Accept-Encoding` = apply(immutable.Seq(encodings: _*))
  implicit val encodingsRenderer = Renderer.defaultSeqRenderer[HttpEncodingRange] // cache
}
final case class `Accept-Encoding`(encodings: immutable.Seq[HttpEncodingRange]) extends jm.headers.AcceptEncoding
  with RequestHeader {
  import `Accept-Encoding`.encodingsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Accept-Encoding`

  /** Java API */
  def getEncodings: Iterable[jm.headers.HttpEncodingRange] = encodings.asJava
}

// http://tools.ietf.org/html/rfc7231#section-5.3.5
object `Accept-Language` extends ModeledCompanion[`Accept-Language`] {
  def apply(first: LanguageRange, more: LanguageRange*): `Accept-Language` = apply(immutable.Seq(first +: more: _*))
  implicit val languagesRenderer = Renderer.defaultSeqRenderer[LanguageRange] // cache
}
final case class `Accept-Language`(languages: immutable.Seq[LanguageRange]) extends jm.headers.AcceptLanguage
  with RequestHeader {
  require(languages.nonEmpty, "languages must not be empty")
  import `Accept-Language`.languagesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ languages
  protected def companion = `Accept-Language`

  /** Java API */
  def getLanguages: Iterable[jm.headers.LanguageRange] = languages.asJava
}

// http://tools.ietf.org/html/rfc7233#section-2.3
object `Accept-Ranges` extends ModeledCompanion[`Accept-Ranges`] {
  def apply(rangeUnits: RangeUnit*): `Accept-Ranges` = apply(immutable.Seq(rangeUnits: _*))
  implicit val rangeUnitsRenderer = Renderer.defaultSeqRenderer[RangeUnit] // cache
}
final case class `Accept-Ranges`(rangeUnits: immutable.Seq[RangeUnit]) extends jm.headers.AcceptRanges
  with ResponseHeader {
  import `Accept-Ranges`.rangeUnitsRenderer
  def renderValue[R <: Rendering](r: R): r.type = if (rangeUnits.isEmpty) r ~~ "none" else r ~~ rangeUnits
  protected def companion = `Accept-Ranges`

  /** Java API */
  def getRangeUnits: Iterable[jm.headers.RangeUnit] = rangeUnits.asJava
}

// http://www.w3.org/TR/cors/#access-control-allow-credentials-response-header
object `Access-Control-Allow-Credentials` extends ModeledCompanion[`Access-Control-Allow-Credentials`]
final case class `Access-Control-Allow-Credentials`(allow: Boolean)
  extends jm.headers.AccessControlAllowCredentials with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ allow.toString
  protected def companion = `Access-Control-Allow-Credentials`
}

// http://www.w3.org/TR/cors/#access-control-allow-headers-response-header
object `Access-Control-Allow-Headers` extends ModeledCompanion[`Access-Control-Allow-Headers`] {
  def apply(headers: String*): `Access-Control-Allow-Headers` = apply(immutable.Seq(headers: _*))
  implicit val headersRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class `Access-Control-Allow-Headers`(headers: immutable.Seq[String])
  extends jm.headers.AccessControlAllowHeaders with ResponseHeader {
  import `Access-Control-Allow-Headers`.headersRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Allow-Headers`

  /** Java API */
  def getHeaders: Iterable[String] = headers.asJava
}

// http://www.w3.org/TR/cors/#access-control-allow-methods-response-header
object `Access-Control-Allow-Methods` extends ModeledCompanion[`Access-Control-Allow-Methods`] {
  def apply(methods: HttpMethod*): `Access-Control-Allow-Methods` = apply(immutable.Seq(methods: _*))
  implicit val methodsRenderer = Renderer.defaultSeqRenderer[HttpMethod] // cache
}
final case class `Access-Control-Allow-Methods`(methods: immutable.Seq[HttpMethod])
  extends jm.headers.AccessControlAllowMethods with ResponseHeader {
  import `Access-Control-Allow-Methods`.methodsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ methods
  protected def companion = `Access-Control-Allow-Methods`

  /** Java API */
  def getMethods: Iterable[jm.HttpMethod] = methods.asJava
}

// http://www.w3.org/TR/cors/#access-control-allow-origin-response-header
object `Access-Control-Allow-Origin` extends ModeledCompanion[`Access-Control-Allow-Origin`] {
  val `*` = forRange(HttpOriginRange.`*`)
  val `null` = forRange(HttpOriginRange())
  def apply(origin: HttpOrigin) = forRange(HttpOriginRange(origin))

  /**
   * Creates an `Access-Control-Allow-Origin` header for the given origin range.
   *
   * CAUTION: Even though allowed by the spec (http://www.w3.org/TR/cors/#access-control-allow-origin-response-header)
   * `Access-Control-Allow-Origin` headers with more than a single origin appear to be largely unsupported in the field.
   * Make sure to thoroughly test such usages with all expected clients!
   */
  def forRange(range: HttpOriginRange) = new `Access-Control-Allow-Origin`(range)
}
final case class `Access-Control-Allow-Origin` private (range: HttpOriginRange)
  extends jm.headers.AccessControlAllowOrigin with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ range
  protected def companion = `Access-Control-Allow-Origin`
}

// http://www.w3.org/TR/cors/#access-control-expose-headers-response-header
object `Access-Control-Expose-Headers` extends ModeledCompanion[`Access-Control-Expose-Headers`] {
  def apply(headers: String*): `Access-Control-Expose-Headers` = apply(immutable.Seq(headers: _*))
  implicit val headersRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class `Access-Control-Expose-Headers`(headers: immutable.Seq[String])
  extends jm.headers.AccessControlExposeHeaders with ResponseHeader {
  import `Access-Control-Expose-Headers`.headersRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Expose-Headers`

  /** Java API */
  def getHeaders: Iterable[String] = headers.asJava
}

// http://www.w3.org/TR/cors/#access-control-max-age-response-header
object `Access-Control-Max-Age` extends ModeledCompanion[`Access-Control-Max-Age`]
final case class `Access-Control-Max-Age`(deltaSeconds: Long) extends jm.headers.AccessControlMaxAge
  with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ deltaSeconds
  protected def companion = `Access-Control-Max-Age`
}

// http://www.w3.org/TR/cors/#access-control-request-headers-request-header
object `Access-Control-Request-Headers` extends ModeledCompanion[`Access-Control-Request-Headers`] {
  def apply(headers: String*): `Access-Control-Request-Headers` = apply(immutable.Seq(headers: _*))
  implicit val headersRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class `Access-Control-Request-Headers`(headers: immutable.Seq[String])
  extends jm.headers.AccessControlRequestHeaders with RequestHeader {
  import `Access-Control-Request-Headers`.headersRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Request-Headers`

  /** Java API */
  def getHeaders: Iterable[String] = headers.asJava
}

// http://www.w3.org/TR/cors/#access-control-request-method-request-header
object `Access-Control-Request-Method` extends ModeledCompanion[`Access-Control-Request-Method`]
final case class `Access-Control-Request-Method`(method: HttpMethod) extends jm.headers.AccessControlRequestMethod
  with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ method
  protected def companion = `Access-Control-Request-Method`
}

// http://tools.ietf.org/html/rfc7234#section-5.1
object Age extends ModeledCompanion[Age]
final case class Age(deltaSeconds: Long) extends jm.headers.Age with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ deltaSeconds
  protected def companion = Age
}

// http://tools.ietf.org/html/rfc7231#section-7.4.1
object Allow extends ModeledCompanion[Allow] {
  def apply(methods: HttpMethod*): Allow = apply(immutable.Seq(methods: _*))
  implicit val methodsRenderer = Renderer.defaultSeqRenderer[HttpMethod] // cache
}
final case class Allow(methods: immutable.Seq[HttpMethod]) extends jm.headers.Allow with ResponseHeader {
  import Allow.methodsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ methods
  protected def companion = Allow

  /** Java API */
  def getMethods: Iterable[jm.HttpMethod] = methods.asJava
}

// http://tools.ietf.org/html/rfc7235#section-4.2
object Authorization extends ModeledCompanion[Authorization]
final case class Authorization(credentials: HttpCredentials) extends jm.headers.Authorization with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ credentials
  protected def companion = Authorization
}

// http://tools.ietf.org/html/rfc7234#section-5.2
object `Cache-Control` extends ModeledCompanion[`Cache-Control`] {
  def apply(first: CacheDirective, more: CacheDirective*): `Cache-Control` = apply(immutable.Seq(first +: more: _*))
  implicit val directivesRenderer = Renderer.defaultSeqRenderer[CacheDirective] // cache
}
final case class `Cache-Control`(directives: immutable.Seq[CacheDirective]) extends jm.headers.CacheControl
  with RequestResponseHeader {
  require(directives.nonEmpty, "directives must not be empty")
  import `Cache-Control`.directivesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ directives
  protected def companion = `Cache-Control`

  /** Java API */
  def getDirectives: Iterable[jm.headers.CacheDirective] = directives.asJava
}

// http://tools.ietf.org/html/rfc7230#section-6.1
object Connection extends ModeledCompanion[Connection] {
  def apply(first: String, more: String*): Connection = apply(immutable.Seq(first +: more: _*))
  implicit val tokensRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class Connection(tokens: immutable.Seq[String]) extends RequestResponseHeader {
  require(tokens.nonEmpty, "tokens must not be empty")
  import Connection.tokensRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ tokens
  def hasClose = has("close")
  def hasKeepAlive = has("keep-alive")
  def hasUpgrade = has("upgrade")
  def append(tokens: immutable.Seq[String]) = Connection(this.tokens ++ tokens)
  @tailrec private def has(item: String, ix: Int = 0): Boolean =
    if (ix < tokens.length)
      if (tokens(ix) equalsIgnoreCase item) true
      else has(item, ix + 1)
    else false
  protected def companion = Connection

  /** Java API */
  def getTokens: Iterable[String] = tokens.asJava
}

// http://tools.ietf.org/html/rfc7230#section-3.3.2
object `Content-Length` extends ModeledCompanion[`Content-Length`]
/**
 * Instances of this class will only be created transiently during header parsing and will never appear
 * in HttpMessage.header. To access the Content-Length, see subclasses of HttpEntity.
 */
final case class `Content-Length` private[http] (length: Long) extends RequestResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ length
  protected def companion = `Content-Length`
}

// http://tools.ietf.org/html/rfc6266
object `Content-Disposition` extends ModeledCompanion[`Content-Disposition`]
final case class `Content-Disposition`(dispositionType: ContentDispositionType, params: Map[String, String] = Map.empty)
  extends jm.headers.ContentDisposition with RequestResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = {
    r ~~ dispositionType
    params foreach { case (k, v) ⇒ r ~~ "; " ~~ k ~~ '=' ~~# v }
    r
  }
  protected def companion = `Content-Disposition`

  /** Java API */
  def getParams: util.Map[String, String] = params.asJava
}

// http://tools.ietf.org/html/rfc7231#section-3.1.2.2
object `Content-Encoding` extends ModeledCompanion[`Content-Encoding`] {
  def apply(first: HttpEncoding, more: HttpEncoding*): `Content-Encoding` = apply(immutable.Seq(first +: more: _*))
  implicit val encodingsRenderer = Renderer.defaultSeqRenderer[HttpEncoding] // cache
}
final case class `Content-Encoding`(encodings: immutable.Seq[HttpEncoding]) extends jm.headers.ContentEncoding
  with RequestResponseHeader {
  require(encodings.nonEmpty, "encodings must not be empty")
  import `Content-Encoding`.encodingsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Content-Encoding`

  /** Java API */
  def getEncodings: Iterable[jm.headers.HttpEncoding] = encodings.asJava
}

// http://tools.ietf.org/html/rfc7233#section-4.2
object `Content-Range` extends ModeledCompanion[`Content-Range`] {
  def apply(byteContentRange: ByteContentRange): `Content-Range` = apply(RangeUnits.Bytes, byteContentRange)
}
final case class `Content-Range`(rangeUnit: RangeUnit, contentRange: ContentRange) extends jm.headers.ContentRange
  with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ rangeUnit ~~ ' ' ~~ contentRange
  protected def companion = `Content-Range`
}

// http://tools.ietf.org/html/rfc7231#section-3.1.1.5
object `Content-Type` extends ModeledCompanion[`Content-Type`]
/**
 * Instances of this class will only be created transiently during header parsing and will never appear
 * in HttpMessage.header. To access the Content-Type, see subclasses of HttpEntity.
 */
final case class `Content-Type` private[http] (contentType: ContentType) extends jm.headers.ContentType
  with RequestResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ contentType
  protected def companion = `Content-Type`
}

// https://tools.ietf.org/html/rfc6265#section-4.2
object Cookie extends ModeledCompanion[Cookie] {
  def apply(first: HttpCookiePair, more: HttpCookiePair*): Cookie = apply(immutable.Seq(first +: more: _*))
  def apply(name: String, value: String): Cookie = apply(HttpCookiePair(name, value))
  def apply(values: (String, String)*): Cookie = apply(values.map(HttpCookiePair(_)).toList)
  implicit val cookiePairsRenderer = Renderer.seqRenderer[HttpCookiePair](separator = "; ") // cache
}
final case class Cookie(cookies: immutable.Seq[HttpCookiePair]) extends jm.headers.Cookie with RequestHeader {
  require(cookies.nonEmpty, "cookies must not be empty")
  import Cookie.cookiePairsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ cookies
  protected def companion = Cookie

  /** Java API */
  def getCookies: Iterable[jm.headers.HttpCookiePair] = cookies.asJava
}

// http://tools.ietf.org/html/rfc7231#section-7.1.1.2
object Date extends ModeledCompanion[Date]
final case class Date(date: DateTime) extends jm.headers.Date with RequestResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = Date
}

/**
 * INTERNAL API
 */
private[headers] object EmptyCompanion extends ModeledCompanion[EmptyHeader.type]
/**
 * INTERNAL API
 */
private[http] object EmptyHeader extends SyntheticHeader {
  def renderValue[R <: Rendering](r: R): r.type = r
  protected def companion: ModeledCompanion[EmptyHeader.type] = EmptyCompanion
}

// http://tools.ietf.org/html/rfc7232#section-2.3
object ETag extends ModeledCompanion[ETag] {
  def apply(tag: String, weak: Boolean = false): ETag = ETag(EntityTag(tag, weak))

}
final case class ETag(etag: EntityTag) extends jm.headers.ETag with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ etag
  protected def companion = ETag
}

// http://tools.ietf.org/html/rfc7231#section-5.1.1
object Expect extends ModeledCompanion[Expect] {
  val `100-continue` = new Expect() {}
}
sealed abstract case class Expect private () extends RequestHeader {
  final def renderValue[R <: Rendering](r: R): r.type = r ~~ "100-continue"
  protected def companion = Expect
}

// http://tools.ietf.org/html/rfc7234#section-5.3
object Expires extends ModeledCompanion[Expires]
final case class Expires(date: DateTime) extends jm.headers.Expires with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = Expires
}

// http://tools.ietf.org/html/rfc7230#section-5.4
object Host extends ModeledCompanion[Host] {
  def apply(authority: Uri.Authority): Host = apply(authority.host, authority.port)
  def apply(address: InetSocketAddress): Host = apply(address.getHostString, address.getPort)
  def apply(host: String): Host = apply(host, 0)
  def apply(host: String, port: Int): Host = apply(Uri.Host(host), port)
  val empty = Host("")
}
final case class Host(host: Uri.Host, port: Int = 0) extends jm.headers.Host with RequestHeader {
  import UriRendering.HostRenderer
  require((port >> 16) == 0, "Illegal port: " + port)
  def isEmpty = host.isEmpty
  def renderValue[R <: Rendering](r: R): r.type = if (port > 0) r ~~ host ~~ ':' ~~ port else r ~~ host
  protected def companion = Host
  def equalsIgnoreCase(other: Host): Boolean = host.equalsIgnoreCase(other.host) && port == other.port
}

// http://tools.ietf.org/html/rfc7232#section-3.1
object `If-Match` extends ModeledCompanion[`If-Match`] {
  val `*` = `If-Match`(EntityTagRange.`*`)
  def apply(first: EntityTag, more: EntityTag*): `If-Match` =
    `If-Match`(EntityTagRange(first +: more: _*))
}
final case class `If-Match`(m: EntityTagRange) extends jm.headers.IfMatch with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ m
  protected def companion = `If-Match`
}

// http://tools.ietf.org/html/rfc7232#section-3.3
object `If-Modified-Since` extends ModeledCompanion[`If-Modified-Since`]
final case class `If-Modified-Since`(date: DateTime) extends jm.headers.IfModifiedSince with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `If-Modified-Since`
}

// http://tools.ietf.org/html/rfc7232#section-3.2
object `If-None-Match` extends ModeledCompanion[`If-None-Match`] {
  val `*` = `If-None-Match`(EntityTagRange.`*`)
  def apply(first: EntityTag, more: EntityTag*): `If-None-Match` =
    `If-None-Match`(EntityTagRange(first +: more: _*))
}
final case class `If-None-Match`(m: EntityTagRange) extends jm.headers.IfNoneMatch with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ m
  protected def companion = `If-None-Match`
}

// http://tools.ietf.org/html/rfc7233#section-3.2
object `If-Range` extends ModeledCompanion[`If-Range`] {
  def apply(tag: EntityTag): `If-Range` = apply(Left(tag))
  def apply(timestamp: DateTime): `If-Range` = apply(Right(timestamp))
}
final case class `If-Range`(entityTagOrDateTime: Either[EntityTag, DateTime]) extends RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type =
    entityTagOrDateTime match {
      case Left(tag)       ⇒ r ~~ tag
      case Right(dateTime) ⇒ dateTime.renderRfc1123DateTimeString(r)
    }
  protected def companion = `If-Range`
}

// http://tools.ietf.org/html/rfc7232#section-3.4
object `If-Unmodified-Since` extends ModeledCompanion[`If-Unmodified-Since`]
final case class `If-Unmodified-Since`(date: DateTime) extends jm.headers.IfUnmodifiedSince with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `If-Unmodified-Since`
}

// http://tools.ietf.org/html/rfc7232#section-2.2
object `Last-Modified` extends ModeledCompanion[`Last-Modified`]
final case class `Last-Modified`(date: DateTime) extends jm.headers.LastModified with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `Last-Modified`
}

// http://tools.ietf.org/html/rfc5988#section-5
object Link extends ModeledCompanion[Link] {
  def apply(uri: Uri, first: LinkParam, more: LinkParam*): Link = apply(immutable.Seq(LinkValue(uri, first +: more: _*)))
  def apply(values: LinkValue*): Link = apply(immutable.Seq(values: _*))
  implicit val valuesRenderer = Renderer.defaultSeqRenderer[LinkValue] // cache
}
final case class Link(values: immutable.Seq[LinkValue]) extends jm.headers.Link with RequestResponseHeader {
  import Link.valuesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ values
  protected def companion = Link

  /** Java API */
  def getValues: Iterable[jm.headers.LinkValue] = values.asJava
}

// http://tools.ietf.org/html/rfc7231#section-7.1.2
object Location extends ModeledCompanion[Location]
final case class Location(uri: Uri) extends jm.headers.Location with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = { import UriRendering.UriRenderer; r ~~ uri }
  protected def companion = Location

  /** Java API */
  def getUri: akka.http.javadsl.model.Uri = uri.asJava
}

// http://tools.ietf.org/html/rfc6454#section-7
object Origin extends ModeledCompanion[Origin] {
  def apply(origins: HttpOrigin*): Origin = apply(immutable.Seq(origins: _*))
}
final case class Origin(origins: immutable.Seq[HttpOrigin]) extends jm.headers.Origin with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = if (origins.isEmpty) r ~~ "null" else r ~~ origins
  protected def companion = Origin

  /** Java API */
  def getOrigins: Iterable[jm.headers.HttpOrigin] = origins.asJava
}

// http://tools.ietf.org/html/rfc7235#section-4.3
object `Proxy-Authenticate` extends ModeledCompanion[`Proxy-Authenticate`] {
  def apply(first: HttpChallenge, more: HttpChallenge*): `Proxy-Authenticate` = apply(immutable.Seq(first +: more: _*))
  implicit val challengesRenderer = Renderer.defaultSeqRenderer[HttpChallenge] // cache
}
final case class `Proxy-Authenticate`(challenges: immutable.Seq[HttpChallenge]) extends jm.headers.ProxyAuthenticate
  with ResponseHeader {
  require(challenges.nonEmpty, "challenges must not be empty")
  import `Proxy-Authenticate`.challengesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ challenges
  protected def companion = `Proxy-Authenticate`

  /** Java API */
  def getChallenges: Iterable[jm.headers.HttpChallenge] = challenges.asJava
}

// http://tools.ietf.org/html/rfc7235#section-4.4
object `Proxy-Authorization` extends ModeledCompanion[`Proxy-Authorization`]
final case class `Proxy-Authorization`(credentials: HttpCredentials) extends jm.headers.ProxyAuthorization
  with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ credentials
  protected def companion = `Proxy-Authorization`
}

// http://tools.ietf.org/html/rfc7233#section-3.1
object Range extends ModeledCompanion[Range] {
  def apply(first: ByteRange, more: ByteRange*): Range = apply(immutable.Seq(first +: more: _*))
  def apply(ranges: immutable.Seq[ByteRange]): Range = Range(RangeUnits.Bytes, ranges)
  implicit val rangesRenderer = Renderer.defaultSeqRenderer[ByteRange] // cache
}
final case class Range(rangeUnit: RangeUnit, ranges: immutable.Seq[ByteRange]) extends jm.headers.Range
  with RequestHeader {
  require(ranges.nonEmpty, "ranges must not be empty")
  import Range.rangesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ rangeUnit ~~ '=' ~~ ranges
  protected def companion = Range

  /** Java API */
  def getRanges: Iterable[jm.headers.ByteRange] = ranges.asJava
}

final case class RawHeader(name: String, value: String) extends jm.headers.RawHeader {
  def renderInRequests = true
  def renderInResponses = true
  val lowercaseName = name.toRootLowerCase
  def render[R <: Rendering](r: R): r.type = r ~~ name ~~ ':' ~~ ' ' ~~ value
}
object RawHeader {
  def unapply[H <: HttpHeader](customHeader: H): Option[(String, String)] =
    Some(customHeader.name -> customHeader.value)
}

object `Raw-Request-URI` extends ModeledCompanion[`Raw-Request-URI`]
final case class `Raw-Request-URI`(uri: String) extends jm.headers.RawRequestURI with SyntheticHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ uri
  protected def companion = `Raw-Request-URI`
}

object `Remote-Address` extends ModeledCompanion[`Remote-Address`]
final case class `Remote-Address`(address: RemoteAddress) extends jm.headers.RemoteAddress with SyntheticHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ address
  protected def companion = `Remote-Address`
}

// http://tools.ietf.org/html/rfc7231#section-5.5.2
object Referer extends ModeledCompanion[Referer]
final case class Referer(uri: Uri) extends jm.headers.Referer with RequestHeader {
  require(uri.fragment.isEmpty, "Referer header URI must not contain a fragment")
  require(uri.authority.userinfo.isEmpty, "Referer header URI must not contain a userinfo component")

  def renderValue[R <: Rendering](r: R): r.type = { import UriRendering.UriRenderer; r ~~ uri }
  protected def companion = Referer

  /** Java API */
  def getUri: akka.http.javadsl.model.Uri = uri.asJava
}

/**
 * INTERNAL API
 */
// http://tools.ietf.org/html/rfc6455#section-4.3
private[http] object `Sec-WebSocket-Accept` extends ModeledCompanion[`Sec-WebSocket-Accept`] {
  // Defined at http://tools.ietf.org/html/rfc6455#section-4.2.2
  val MagicGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** Generates the matching accept header for this key */
  def forKey(key: `Sec-WebSocket-Key`): `Sec-WebSocket-Accept` = {
    val sha1 = MessageDigest.getInstance("sha1")
    val salted = key.key + MagicGuid
    val hash = sha1.digest(salted.asciiBytes)
    val acceptKey = Base64.rfc2045().encodeToString(hash, false)
    `Sec-WebSocket-Accept`(acceptKey)
  }
}
/**
 * INTERNAL API
 */
private[http] final case class `Sec-WebSocket-Accept`(key: String) extends ResponseHeader {
  protected[http] def renderValue[R <: Rendering](r: R): r.type = r ~~ key

  protected def companion = `Sec-WebSocket-Accept`
}

/**
 * INTERNAL API
 */
// http://tools.ietf.org/html/rfc6455#section-4.3
private[http] object `Sec-WebSocket-Extensions` extends ModeledCompanion[`Sec-WebSocket-Extensions`] {
  implicit val extensionsRenderer = Renderer.defaultSeqRenderer[WebSocketExtension]
}
/**
 * INTERNAL API
 */
private[http] final case class `Sec-WebSocket-Extensions`(extensions: immutable.Seq[WebSocketExtension])
  extends ResponseHeader {
  require(extensions.nonEmpty, "Sec-WebSocket-Extensions.extensions must not be empty")
  import `Sec-WebSocket-Extensions`.extensionsRenderer
  protected[http] def renderValue[R <: Rendering](r: R): r.type = r ~~ extensions
  protected def companion = `Sec-WebSocket-Extensions`
}

// http://tools.ietf.org/html/rfc6455#section-4.3
/**
 * INTERNAL API
 */
private[http] object `Sec-WebSocket-Key` extends ModeledCompanion[`Sec-WebSocket-Key`] {
  def apply(keyBytes: Array[Byte]): `Sec-WebSocket-Key` = {
    require(keyBytes.length == 16, s"Sec-WebSocket-Key keyBytes must have length 16 but had ${keyBytes.length}")
    `Sec-WebSocket-Key`(Base64.rfc2045().encodeToString(keyBytes, false))
  }
}
/**
 * INTERNAL API
 */
private[http] final case class `Sec-WebSocket-Key`(key: String) extends RequestHeader {
  protected[http] def renderValue[R <: Rendering](r: R): r.type = r ~~ key

  protected def companion = `Sec-WebSocket-Key`

  /**
   * Checks if the key value is valid according to the WebSocket specification, i.e.
   * if the String is a Base64 representation of 16 bytes.
   */
  def isValid: Boolean = Try(Base64.rfc2045().decode(key)).toOption.exists(_.length == 16)
}

// http://tools.ietf.org/html/rfc6455#section-4.3
/**
 * INTERNAL API
 */
private[http] object `Sec-WebSocket-Protocol` extends ModeledCompanion[`Sec-WebSocket-Protocol`] {
  implicit val protocolsRenderer = Renderer.defaultSeqRenderer[String]
}
/**
 * INTERNAL API
 */
private[http] final case class `Sec-WebSocket-Protocol`(protocols: immutable.Seq[String])
  extends RequestResponseHeader {
  require(protocols.nonEmpty, "Sec-WebSocket-Protocol.protocols must not be empty")
  import `Sec-WebSocket-Protocol`.protocolsRenderer
  protected[http] def renderValue[R <: Rendering](r: R): r.type = r ~~ protocols
  protected def companion = `Sec-WebSocket-Protocol`
}

// http://tools.ietf.org/html/rfc6455#section-4.3
/**
 * INTERNAL API
 */
private[http] object `Sec-WebSocket-Version` extends ModeledCompanion[`Sec-WebSocket-Version`] {
  implicit val versionsRenderer = Renderer.defaultSeqRenderer[Int]
}
/**
 * INTERNAL API
 */
private[http] final case class `Sec-WebSocket-Version`(versions: immutable.Seq[Int])
  extends RequestResponseHeader {
  require(versions.nonEmpty, "Sec-WebSocket-Version.versions must not be empty")
  require(versions.forall(v ⇒ v >= 0 && v <= 255), s"Sec-WebSocket-Version.versions must be in the range 0 <= version <= 255 but were $versions")
  import `Sec-WebSocket-Version`.versionsRenderer
  protected[http] def renderValue[R <: Rendering](r: R): r.type = r ~~ versions
  def hasVersion(versionNumber: Int): Boolean = versions contains versionNumber
  protected def companion = `Sec-WebSocket-Version`
}

// http://tools.ietf.org/html/rfc7231#section-7.4.2
object Server extends ModeledCompanion[Server] {
  def apply(products: String): Server = apply(ProductVersion.parseMultiple(products))
  def apply(first: ProductVersion, more: ProductVersion*): Server = apply(immutable.Seq(first +: more: _*))
  implicit val productsRenderer = Renderer.seqRenderer[ProductVersion](separator = " ") // cache
}
final case class Server(products: immutable.Seq[ProductVersion]) extends jm.headers.Server with ResponseHeader {
  require(products.nonEmpty, "products must not be empty")
  import Server.productsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ products
  protected def companion = Server

  /** Java API */
  def getProducts: Iterable[jm.headers.ProductVersion] = products.asJava
}

// https://tools.ietf.org/html/rfc6797
object `Strict-Transport-Security` extends ModeledCompanion[`Strict-Transport-Security`] {
  def apply(maxAge: Long, includeSubDomains: Option[Boolean]) = new `Strict-Transport-Security`(maxAge, includeSubDomains.getOrElse(false))
}
final case class `Strict-Transport-Security`(maxAge: Long, includeSubDomains: Boolean = false) extends jm.headers.StrictTransportSecurity with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = {
    r ~~ "max-age=" ~~ maxAge
    if (includeSubDomains) r ~~ "; includeSubDomains"
    r
  }
  protected def companion = `Strict-Transport-Security`
}

// https://tools.ietf.org/html/rfc6265
object `Set-Cookie` extends ModeledCompanion[`Set-Cookie`]
final case class `Set-Cookie`(cookie: HttpCookie) extends jm.headers.SetCookie with ResponseHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ cookie
  protected def companion = `Set-Cookie`
}

object `Timeout-Access` extends ModeledCompanion[`Timeout-Access`]
final case class `Timeout-Access`(timeoutAccess: akka.http.scaladsl.TimeoutAccess)
  extends jm.headers.TimeoutAccess with SyntheticHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ timeoutAccess.toString
  protected def companion = `Timeout-Access`
}

/**
 * Model for the synthetic `Tls-Session-Info` header which carries the SSLSession of the connection
 * the message carrying this header was received with.
 *
 * This header will only be added if it enabled in the configuration by setting
 *
 * ```
 * akka.http.[client|server].parsing.tls-session-info-header = on
 * ```
 */
object `Tls-Session-Info` extends ModeledCompanion[`Tls-Session-Info`]
final case class `Tls-Session-Info`(session: SSLSession) extends jm.headers.TlsSessionInfo with SyntheticHeader
  with ScalaSessionAPI {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ session.toString
  protected def companion = `Tls-Session-Info`

  /** Java API */
  def getSession: SSLSession = session
}

// http://tools.ietf.org/html/rfc7230#section-3.3.1
object `Transfer-Encoding` extends ModeledCompanion[`Transfer-Encoding`] {
  def apply(first: TransferEncoding, more: TransferEncoding*): `Transfer-Encoding` = apply(immutable.Seq(first +: more: _*))
  implicit val encodingsRenderer = Renderer.defaultSeqRenderer[TransferEncoding] // cache
}
final case class `Transfer-Encoding`(encodings: immutable.Seq[TransferEncoding]) extends jm.headers.TransferEncoding
  with RequestResponseHeader {
  require(encodings.nonEmpty, "encodings must not be empty")
  import `Transfer-Encoding`.encodingsRenderer
  def isChunked: Boolean = encodings.last == TransferEncodings.chunked
  def withChunked: `Transfer-Encoding` = if (isChunked) this else `Transfer-Encoding`(encodings :+ TransferEncodings.chunked)
  def withChunkedPeeled: Option[`Transfer-Encoding`] =
    if (isChunked) {
      encodings.init match {
        case Nil       ⇒ None
        case remaining ⇒ Some(`Transfer-Encoding`(remaining))
      }
    } else Some(this)
  def append(encodings: immutable.Seq[TransferEncoding]) = `Transfer-Encoding`(this.encodings ++ encodings)
  def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Transfer-Encoding`

  /** Java API */
  def getEncodings: Iterable[jm.TransferEncoding] = encodings.asJava
}

// http://tools.ietf.org/html/rfc7230#section-6.7
object Upgrade extends ModeledCompanion[Upgrade] {
  implicit val protocolsRenderer = Renderer.defaultSeqRenderer[UpgradeProtocol]
}
final case class Upgrade(protocols: immutable.Seq[UpgradeProtocol]) extends RequestResponseHeader {
  import Upgrade.protocolsRenderer
  protected[http] def renderValue[R <: Rendering](r: R): r.type = r ~~ protocols

  protected def companion = Upgrade

  def hasWebSocket: Boolean = protocols.exists(_.name equalsIgnoreCase "websocket")
}

// http://tools.ietf.org/html/rfc7231#section-5.5.3
object `User-Agent` extends ModeledCompanion[`User-Agent`] {
  def apply(products: String): `User-Agent` = apply(ProductVersion.parseMultiple(products))
  def apply(first: ProductVersion, more: ProductVersion*): `User-Agent` = apply(immutable.Seq(first +: more: _*))
  implicit val productsRenderer = Renderer.seqRenderer[ProductVersion](separator = " ") // cache
}
final case class `User-Agent`(products: immutable.Seq[ProductVersion]) extends jm.headers.UserAgent with RequestHeader {
  require(products.nonEmpty, "products must not be empty")
  import `User-Agent`.productsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ products
  protected def companion = `User-Agent`

  /** Java API */
  def getProducts: Iterable[jm.headers.ProductVersion] = products.asJava
}

// http://tools.ietf.org/html/rfc7235#section-4.1
object `WWW-Authenticate` extends ModeledCompanion[`WWW-Authenticate`] {
  def apply(first: HttpChallenge, more: HttpChallenge*): `WWW-Authenticate` = apply(immutable.Seq(first +: more: _*))
  implicit val challengesRenderer = Renderer.defaultSeqRenderer[HttpChallenge] // cache
}
final case class `WWW-Authenticate`(challenges: immutable.Seq[HttpChallenge]) extends jm.headers.WWWAuthenticate
  with ResponseHeader {
  require(challenges.nonEmpty, "challenges must not be empty")
  import `WWW-Authenticate`.challengesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ challenges
  protected def companion = `WWW-Authenticate`

  /** Java API */
  def getChallenges: Iterable[jm.headers.HttpChallenge] = challenges.asJava
}

// http://en.wikipedia.org/wiki/X-Forwarded-For
object `X-Forwarded-For` extends ModeledCompanion[`X-Forwarded-For`] {
  def apply(first: RemoteAddress, more: RemoteAddress*): `X-Forwarded-For` = apply(immutable.Seq(first +: more: _*))
  implicit val addressesRenderer = Renderer.defaultSeqRenderer[RemoteAddress] // cache
}
final case class `X-Forwarded-For`(addresses: immutable.Seq[RemoteAddress]) extends jm.headers.XForwardedFor
  with RequestHeader {
  require(addresses.nonEmpty, "addresses must not be empty")
  import `X-Forwarded-For`.addressesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ addresses
  protected def companion = `X-Forwarded-For`

  /** Java API */
  def getAddresses: Iterable[jm.RemoteAddress] = addresses.asJava
}

object `X-Real-Ip` extends ModeledCompanion[`X-Real-Ip`]
final case class `X-Real-Ip`(address: RemoteAddress) extends jm.headers.XRealIp
  with RequestHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ address
  protected def companion = `X-Real-Ip`
}
