/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import java.net.InetSocketAddress
import scala.annotation.{ tailrec, implicitNotFound }
import scala.collection.immutable
import akka.http.util._

object AllowProtectedHeaderCreation {
  @implicitNotFound("Headers of this type are managed automatically by akka-http-core. If you are sure that creating " +
    "instances manually is required in your use case `import AllowProtectedHeaderCreation.enable` to override this warning.")
  sealed trait Enabled
  implicit def enable: Enabled = null
}
import AllowProtectedHeaderCreation.enable

sealed abstract class ModeledCompanion extends Renderable {
  val name = getClass.getSimpleName.replace("$minus", "-").dropRight(1) // trailing $
  val lowercaseName = name.toLowerCase
  private[this] val nameBytes = name.getAsciiBytes
  final def render[R <: Rendering](r: R): r.type = r ~~ nameBytes ~~ ':' ~~ ' '
}

sealed abstract class ModeledHeader extends HttpHeader with Serializable {
  def name: String = companion.name
  def value: String = renderValue(new StringRendering).get
  def lowercaseName: String = companion.lowercaseName
  final def render[R <: Rendering](r: R): r.type = renderValue(r ~~ companion)
  protected def renderValue[R <: Rendering](r: R): r.type
  protected def companion: ModeledCompanion
}

// http://tools.ietf.org/html/rfc7231#section-5.3.2
object Accept extends ModeledCompanion {
  def apply(mediaRanges: MediaRange*): Accept = apply(immutable.Seq(mediaRanges: _*))
  private[http] implicit val rangesRenderer = Renderer.defaultSeqRenderer[MediaRange] // cache
}
final case class Accept(mediaRanges: immutable.Seq[MediaRange]) extends ModeledHeader {
  import Accept.rangesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ mediaRanges
  protected def companion = Accept
}

// http://tools.ietf.org/html/rfc7231#section-5.3.3
object `Accept-Charset` extends ModeledCompanion {
  def apply(first: HttpCharsetRange, more: HttpCharsetRange*): `Accept-Charset` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val rangesRenderer = Renderer.defaultSeqRenderer[HttpCharsetRange] // cache
}
final case class `Accept-Charset`(charsetRanges: immutable.Seq[HttpCharsetRange]) extends ModeledHeader {
  require(charsetRanges.nonEmpty, "charsetRanges must not be empty")
  import `Accept-Charset`.rangesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ charsetRanges
  protected def companion = `Accept-Charset`
}

// http://tools.ietf.org/html/rfc7231#section-5.3.4
object `Accept-Encoding` extends ModeledCompanion {
  def apply(encodings: HttpEncodingRange*): `Accept-Encoding` = apply(immutable.Seq(encodings: _*))
  private[http] implicit val rangesRenderer = Renderer.defaultSeqRenderer[HttpEncodingRange] // cache
}
final case class `Accept-Encoding`(encodings: immutable.Seq[HttpEncodingRange]) extends ModeledHeader {
  import `Accept-Encoding`.rangesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Accept-Encoding`
}

// http://tools.ietf.org/html/rfc7231#section-5.3.5
object `Accept-Language` extends ModeledCompanion {
  def apply(first: LanguageRange, more: LanguageRange*): `Accept-Language` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val rangesRenderer = Renderer.defaultSeqRenderer[LanguageRange] // cache
}
final case class `Accept-Language`(languages: immutable.Seq[LanguageRange]) extends ModeledHeader {
  require(languages.nonEmpty, "languages must not be empty")
  import `Accept-Language`.rangesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ languages
  protected def companion = `Accept-Language`
}

// http://tools.ietf.org/html/rfc7233#section-2.3
object `Accept-Ranges` extends ModeledCompanion {
  def apply(rangeUnits: RangeUnit*): `Accept-Ranges` = apply(immutable.Seq(rangeUnits: _*))
  private[http] implicit val rangeUnitsRenderer = Renderer.defaultSeqRenderer[RangeUnit] // cache
}
final case class `Accept-Ranges`(rangeUnits: immutable.Seq[RangeUnit]) extends ModeledHeader {
  import `Accept-Ranges`.rangeUnitsRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = if (rangeUnits.isEmpty) r ~~ "none" else r ~~ rangeUnits
  protected def companion = `Accept-Ranges`
}

// http://www.w3.org/TR/cors/#access-control-allow-credentials-response-header
object `Access-Control-Allow-Credentials` extends ModeledCompanion
final case class `Access-Control-Allow-Credentials`(allow: Boolean) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ allow.toString
  protected def companion = `Access-Control-Allow-Credentials`
}

// http://www.w3.org/TR/cors/#access-control-allow-headers-response-header
object `Access-Control-Allow-Headers` extends ModeledCompanion {
  def apply(headers: String*): `Access-Control-Allow-Headers` = apply(immutable.Seq(headers: _*))
  private[http] implicit val headersRenderer = Renderer.defaultSeqRenderer[String]
}
final case class `Access-Control-Allow-Headers`(headers: immutable.Seq[String]) extends ModeledHeader {
  import `Access-Control-Allow-Headers`.headersRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Allow-Headers`
}

// http://www.w3.org/TR/cors/#access-control-allow-methods-response-header
object `Access-Control-Allow-Methods` extends ModeledCompanion {
  def apply(methods: HttpMethod*): `Access-Control-Allow-Methods` = apply(immutable.Seq(methods: _*))
  private[http] implicit val methodsRenderer = Renderer.defaultSeqRenderer[HttpMethod]
}
final case class `Access-Control-Allow-Methods`(methods: immutable.Seq[HttpMethod]) extends ModeledHeader {
  import `Access-Control-Allow-Methods`.methodsRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ methods
  protected def companion = `Access-Control-Allow-Methods`
}

// http://www.w3.org/TR/cors/#access-control-allow-origin-response-header
object `Access-Control-Allow-Origin` extends ModeledCompanion
final case class `Access-Control-Allow-Origin`(range: HttpOriginRange) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ range
  protected def companion = `Access-Control-Allow-Origin`
}

// http://www.w3.org/TR/cors/#access-control-expose-headers-response-header
object `Access-Control-Expose-Headers` extends ModeledCompanion {
  def apply(headers: String*): `Access-Control-Expose-Headers` = apply(immutable.Seq(headers: _*))
  private[http] implicit val headersRenderer = Renderer.defaultSeqRenderer[String]
}
final case class `Access-Control-Expose-Headers`(headers: immutable.Seq[String]) extends ModeledHeader {
  import `Access-Control-Expose-Headers`.headersRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Expose-Headers`
}

// http://www.w3.org/TR/cors/#access-control-max-age-response-header
object `Access-Control-Max-Age` extends ModeledCompanion
final case class `Access-Control-Max-Age`(deltaSeconds: Long) extends ModeledHeader {
  require(deltaSeconds >= 0, "deltaSeconds must be >= 0")
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ deltaSeconds
  protected def companion = `Access-Control-Max-Age`
}

// http://www.w3.org/TR/cors/#access-control-request-headers-request-header
object `Access-Control-Request-Headers` extends ModeledCompanion {
  def apply(headers: String*): `Access-Control-Request-Headers` = apply(immutable.Seq(headers: _*))
  private[http] implicit val headersRenderer = Renderer.defaultSeqRenderer[String]
}
final case class `Access-Control-Request-Headers`(headers: immutable.Seq[String]) extends ModeledHeader {
  import `Access-Control-Request-Headers`.headersRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Request-Headers`
}

// http://www.w3.org/TR/cors/#access-control-request-method-request-header
object `Access-Control-Request-Method` extends ModeledCompanion
final case class `Access-Control-Request-Method`(method: HttpMethod) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ method
  protected def companion = `Access-Control-Request-Method`
}

// http://tools.ietf.org/html/rfc7231#section-7.4.1
object Allow extends ModeledCompanion {
  def apply(methods: HttpMethod*): Allow = apply(immutable.Seq(methods: _*))
  private[http] implicit val methodsRenderer = Renderer.defaultSeqRenderer[HttpMethod]
}
final case class Allow(methods: immutable.Seq[HttpMethod]) extends ModeledHeader {
  import Allow.methodsRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ methods
  protected def companion = Allow
}

// http://tools.ietf.org/html/rfc7235#section-4.2
object Authorization extends ModeledCompanion
final case class Authorization(credentials: HttpCredentials) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ credentials
  protected def companion = Authorization
}

// http://tools.ietf.org/html/rfc7234#section-5.2
object `Cache-Control` extends ModeledCompanion {
  def apply(first: CacheDirective, more: CacheDirective*): `Cache-Control` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val directivesRenderer = Renderer.defaultSeqRenderer[CacheDirective] // cache
}
final case class `Cache-Control`(directives: immutable.Seq[CacheDirective]) extends ModeledHeader {
  require(directives.nonEmpty, "directives must not be empty")
  import `Cache-Control`.directivesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ directives
  protected def companion = `Cache-Control`
}

// http://tools.ietf.org/html/rfc7230#section-6.1
object Connection extends ModeledCompanion {
  def apply(first: String, more: String*): Connection = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val tokensRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class Connection(tokens: immutable.Seq[String]) extends ModeledHeader {
  require(tokens.nonEmpty, "tokens must not be empty")
  import Connection.tokensRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ tokens
  def hasClose = has("close")
  def hasKeepAlive = has("keep-alive")
  @tailrec private def has(item: String, ix: Int = 0): Boolean =
    if (ix < tokens.length)
      if (tokens(ix) equalsIgnoreCase item) true
      else has(item, ix + 1)
    else false
  protected def companion = Connection
}

// http://tools.ietf.org/html/rfc6266
object `Content-Disposition` extends ModeledCompanion
final case class `Content-Disposition`(dispositionType: ContentDispositionType, parameters: Map[String, String] = Map.empty) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = {
    r ~~ dispositionType
    parameters foreach { case (k, v) ⇒ r ~~ "; " ~~ k ~~ '=' ~~# v }
    r
  }
  protected def companion = `Content-Disposition`
}

// http://tools.ietf.org/html/rfc7231#section-3.1.2.2
object `Content-Encoding` extends ModeledCompanion {
  def apply(first: HttpEncoding, more: HttpEncoding*): `Content-Encoding` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val encodingsRenderer = Renderer.defaultSeqRenderer[HttpEncoding] // cache
}
final case class `Content-Encoding`(encodings: immutable.Seq[HttpEncoding]) extends ModeledHeader {
  require(encodings.nonEmpty, "encodings must not be empty")
  import `Content-Encoding`.encodingsRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Content-Encoding`
}

// http://tools.ietf.org/html/rfc7230#section-3.3.2
object `Content-Length` extends ModeledCompanion
final case class `Content-Length`(length: Long)(implicit ev: AllowProtectedHeaderCreation.Enabled) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ length
  protected def companion = `Content-Length`
}

// http://tools.ietf.org/html/rfc7233#section-4.2
object `Content-Range` extends ModeledCompanion {
  def apply(byteContentRange: ByteContentRange): `Content-Range` = apply(RangeUnits.Bytes, byteContentRange)
}
final case class `Content-Range`(rangeUnit: RangeUnit, contentRange: ContentRange) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ rangeUnit ~~ ' ' ~~ contentRange
  protected def companion = `Content-Range`
}

// http://tools.ietf.org/html/rfc7231#section-3.1.1.5
object `Content-Type` extends ModeledCompanion
final case class `Content-Type`(contentType: ContentType)(implicit ev: AllowProtectedHeaderCreation.Enabled) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ contentType
  protected def companion = `Content-Type`
}

// https://tools.ietf.org/html/rfc6265#section-4.2
object Cookie extends ModeledCompanion {
  def apply(first: HttpCookie, more: HttpCookie*): Cookie = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val cookieNameValueOnlyRenderer: Renderer[HttpCookie] = new Renderer[HttpCookie] {
    def render[R <: Rendering](r: R, c: HttpCookie): r.type = r ~~ c.name ~~ '=' ~~ c.content
  }
  private[http] implicit val cookiesRenderer: Renderer[immutable.Seq[HttpCookie]] = Renderer.seqRenderer(separator = "; ") // cache
}
final case class Cookie(cookies: immutable.Seq[HttpCookie]) extends ModeledHeader {
  import Cookie.cookiesRenderer
  require(cookies.nonEmpty, "cookies must not be empty")
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ cookies
  protected def companion = Cookie
}

// http://tools.ietf.org/html/rfc7231#section-7.1.1.2
object Date extends ModeledCompanion
final case class Date(date: DateTime)(implicit ev: AllowProtectedHeaderCreation.Enabled) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = Date
}

// http://tools.ietf.org/html/rfc7232#section-2.3
object ETag extends ModeledCompanion {
  def apply(tag: String, weak: Boolean = false): ETag = ETag(EntityTag(tag, weak))
}
final case class ETag(etag: EntityTag) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ etag
  protected def companion = ETag
}

// http://tools.ietf.org/html/rfc7231#section-5.1.1
object Expect extends ModeledCompanion {
  val `100-continue` = new Expect() {}
}
sealed abstract case class Expect private () extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ "100-continue"
  protected def companion = Expect
}

// http://tools.ietf.org/html/rfc7230#section-5.4
object Host extends ModeledCompanion {
  def apply(address: InetSocketAddress): Host = apply(address.getHostName, address.getPort)
  def apply(host: String): Host = apply(host, 0)
  def apply(host: String, port: Int): Host = apply(Uri.Host(host), port)
  val empty = Host("")
}
final case class Host(host: Uri.Host, port: Int = 0) extends ModeledHeader {
  import UriRendering.HostRenderer
  require((port >> 16) == 0, "Illegal port: " + port)
  def isEmpty = host.isEmpty
  def equalsIgnoreCase(other: Host): Boolean = host.equalsIgnoreCase(other.host) && port == other.port
  protected[akka] def renderValue[R <: Rendering](r: R): r.type = if (port > 0) r ~~ host ~~ ':' ~~ port else r ~~ host
  protected def companion = Host
}

// http://tools.ietf.org/html/rfc7232#section-3.1
object `If-Match` extends ModeledCompanion {
  val `*` = `If-Match`(EntityTagRange.`*`)
  def apply(first: EntityTag, more: EntityTag*): `If-Match` =
    `If-Match`(EntityTagRange(first +: more: _*))
}
final case class `If-Match`(m: EntityTagRange) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ m
  protected def companion = `If-Match`
}

// http://tools.ietf.org/html/rfc7232#section-3.3
object `If-Modified-Since` extends ModeledCompanion
final case class `If-Modified-Since`(date: DateTime) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `If-Modified-Since`
}

// http://tools.ietf.org/html/rfc7232#section-3.2
object `If-None-Match` extends ModeledCompanion {
  val `*` = `If-None-Match`(EntityTagRange.`*`)
  def apply(first: EntityTag, more: EntityTag*): `If-None-Match` =
    `If-None-Match`(EntityTagRange(first +: more: _*))
}
final case class `If-None-Match`(m: EntityTagRange) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ m
  protected def companion = `If-None-Match`
}

// http://tools.ietf.org/html/rfc7232#section-3.5
// http://tools.ietf.org/html/rfc7233#section-3.2
object `If-Range` extends ModeledCompanion {
  def apply(tag: EntityTag): `If-Range` = apply(Left(tag))
  def apply(timestamp: DateTime): `If-Range` = apply(Right(timestamp))
}
final case class `If-Range`(entityTagOrDateTime: Either[EntityTag, DateTime]) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type =
    entityTagOrDateTime match {
      case Left(tag)       ⇒ r ~~ tag
      case Right(dateTime) ⇒ dateTime.renderRfc1123DateTimeString(r)
    }
  protected def companion = `If-Range`
}

// http://tools.ietf.org/html/rfc7232#section-3.4
object `If-Unmodified-Since` extends ModeledCompanion
final case class `If-Unmodified-Since`(date: DateTime) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `If-Unmodified-Since`
}

// http://tools.ietf.org/html/rfc7232#section-2.2
object `Last-Modified` extends ModeledCompanion
final case class `Last-Modified`(date: DateTime) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `Last-Modified`
}

// http://tools.ietf.org/html/rfc5988#section-5
object Link extends ModeledCompanion {
  def apply(uri: Uri, first: LinkParam, more: LinkParam*): Link = apply(immutable.Seq(LinkValue(uri, first +: more: _*)))
  def apply(values: LinkValue*): Link = apply(immutable.Seq(values: _*))
  private[http] implicit val valuesRenderer = Renderer.defaultSeqRenderer[LinkValue]
}
final case class Link(values: immutable.Seq[LinkValue]) extends ModeledHeader {
  import Link.valuesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ values
  protected def companion = Link
}

// http://tools.ietf.org/html/rfc7231#section-7.1.2
object Location extends ModeledCompanion
final case class Location(uri: Uri) extends ModeledHeader {
  import UriRendering.UriRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ uri
  protected def companion = Location
}

// http://tools.ietf.org/html/rfc6454#section-7
object Origin extends ModeledCompanion {
  def apply(first: HttpOrigin, more: HttpOrigin*): Origin = apply(immutable.Seq(first +: more: _*))
}
final case class Origin(origins: immutable.Seq[HttpOrigin]) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ origins
  protected def companion = Origin
}

// http://tools.ietf.org/html/rfc7233#section-3.1
object Range extends ModeledCompanion {
  def apply(first: ByteRange, more: ByteRange*): Range = apply(immutable.Seq(first +: more: _*))
  def apply(ranges: immutable.Seq[ByteRange]): Range = Range(RangeUnits.Bytes, ranges)
  private[http] implicit val rangesRenderer = Renderer.defaultSeqRenderer[ByteRange] // cache
}
final case class Range(rangeUnit: RangeUnit, ranges: immutable.Seq[ByteRange]) extends ModeledHeader {
  import Range.rangesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ rangeUnit ~~ '=' ~~ ranges
  protected def companion = Range
}

// http://tools.ietf.org/html/rfc7235#section-4.3
object `Proxy-Authenticate` extends ModeledCompanion {
  def apply(first: HttpChallenge, more: HttpChallenge*): `Proxy-Authenticate` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val challengesRenderer = Renderer.defaultSeqRenderer[HttpChallenge] // cache
}
final case class `Proxy-Authenticate`(challenges: immutable.Seq[HttpChallenge]) extends ModeledHeader {
  require(challenges.nonEmpty, "challenges must not be empty")
  import `Proxy-Authenticate`.challengesRenderer
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ challenges
  protected def companion = `Proxy-Authenticate`
}

// http://tools.ietf.org/html/rfc7235#section-4.4
object `Proxy-Authorization` extends ModeledCompanion
final case class `Proxy-Authorization`(credentials: HttpCredentials) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ credentials
  protected def companion = `Proxy-Authorization`
}

// custom header we use for transporting the raw request URI either to the application (server-side)
// or to the request rendering stage (client-side)
object `Raw-Request-URI` extends ModeledCompanion
final case class `Raw-Request-URI`(uri: String) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ uri
  protected def companion = `Raw-Request-URI`
}

// custom header we use for optionally transporting the peer's IP in an HTTP header
object `Remote-Address` extends ModeledCompanion {
  def apply(address: String): `Remote-Address` = apply(RemoteAddress(address))
}
final case class `Remote-Address`(address: RemoteAddress) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ address
  protected def companion = `Remote-Address`
}

// http://tools.ietf.org/html/rfc7231#section-7.4.2
object Server extends ModeledCompanion {
  def apply(products: String): Server = apply(ProductVersion.parseMultiple(products))
  def apply(first: ProductVersion, more: ProductVersion*): Server = apply(immutable.Seq(first +: more: _*))
}
final case class Server(products: immutable.Seq[ProductVersion])(implicit ev: AllowProtectedHeaderCreation.Enabled) extends ModeledHeader {
  require(products.nonEmpty, "products must not be empty")
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ products
  protected def companion = Server
}

// https://tools.ietf.org/html/rfc6265
object `Set-Cookie` extends ModeledCompanion
final case class `Set-Cookie`(cookie: HttpCookie) extends ModeledHeader {
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ cookie
  protected def companion = `Set-Cookie`
}

// http://tools.ietf.org/html/rfc7230#section-3.3.1
object `Transfer-Encoding` extends ModeledCompanion {
  def apply(first: TransferEncoding, more: TransferEncoding*): `Transfer-Encoding` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val encodingsRenderer = Renderer.defaultSeqRenderer[TransferEncoding] // cache
}
final case class `Transfer-Encoding`(encodings: immutable.Seq[TransferEncoding])(implicit ev: AllowProtectedHeaderCreation.Enabled) extends ModeledHeader {
  import `Transfer-Encoding`.encodingsRenderer
  require(encodings.nonEmpty, "encodings must not be empty")
  def hasChunked: Boolean = encodings contains TransferEncodings.chunked
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Transfer-Encoding`
}

// http://tools.ietf.org/html/rfc7231#section-5.5.3
object `User-Agent` extends ModeledCompanion {
  def apply(products: String): `User-Agent` = apply(ProductVersion.parseMultiple(products))
  def apply(first: ProductVersion, more: ProductVersion*): `User-Agent` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val productsRenderer = Renderer.seqRenderer[String](separator = " ") // cache
}
final case class `User-Agent`(products: immutable.Seq[ProductVersion])(implicit ev: AllowProtectedHeaderCreation.Enabled) extends ModeledHeader {
  import `User-Agent`.productsRenderer
  require(products.nonEmpty, "products must not be empty")
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ products
  protected def companion = `User-Agent`
}

// http://tools.ietf.org/html/rfc7235#section-4.1
object `WWW-Authenticate` extends ModeledCompanion {
  def apply(first: HttpChallenge, more: HttpChallenge*): `WWW-Authenticate` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val challengesRenderer = Renderer.defaultSeqRenderer[HttpChallenge] // cache
}
final case class `WWW-Authenticate`(challenges: immutable.Seq[HttpChallenge]) extends ModeledHeader {
  import `WWW-Authenticate`.challengesRenderer
  require(challenges.nonEmpty, "challenges must not be empty")
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ challenges
  protected def companion = `WWW-Authenticate`
}

// http://en.wikipedia.org/wiki/X-Forwarded-For
object `X-Forwarded-For` extends ModeledCompanion {
  def apply(first: String, more: String*): `X-Forwarded-For` =
    apply(immutable.Seq((first +: more).map(RemoteAddress.apply): _*))
  def apply(first: RemoteAddress, more: RemoteAddress*): `X-Forwarded-For` = apply(immutable.Seq(first +: more: _*))
  private[http] implicit val addressesRenderer = Renderer.defaultSeqRenderer[RemoteAddress]
}
final case class `X-Forwarded-For`(addresses: immutable.Seq[RemoteAddress]) extends ModeledHeader {
  import `X-Forwarded-For`.addressesRenderer
  require(addresses.nonEmpty, "addresses must not be empty")
  protected def renderValue[R <: Rendering](r: R): r.type = r ~~ addresses
  protected def companion = `X-Forwarded-For`
}

// FIXME: resurrect SSL-Session-Info header once akka.io.SslTlsSupport supports it
final case class RawHeader(name: String, value: String) extends HttpHeader {
  val lowercaseName = name.toLowerCase
  def render[R <: Rendering](r: R): r.type = r ~~ name ~~ ':' ~~ ' ' ~~ value
}
