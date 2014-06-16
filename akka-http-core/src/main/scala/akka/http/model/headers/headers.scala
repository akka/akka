/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import java.net.InetSocketAddress
import scala.annotation.{ tailrec, implicitNotFound }
import scala.collection.immutable
import akka.http.util._

object ProtectedHeaderCreation {
  @implicitNotFound("Headers of this type are managed automatically by akka-http-core. If you are sure that creating " +
    "instances manually is required in your use case `import HttpHeaders.ProtectedHeaderCreation.enable` to override " +
    "this warning.")
  sealed trait Enabled
  implicit def enable: Enabled = null
}
import ProtectedHeaderCreation.enable

sealed abstract class ModeledCompanion extends Renderable {
  val name = getClass.getSimpleName.replace("$minus", "-").dropRight(1) // trailing $
  val lowercaseName = name.toLowerCase
  private[this] val nameBytes = name.getAsciiBytes
  def render[R <: Rendering](r: R): r.type = r ~~ nameBytes ~~ ':' ~~ ' '
}

sealed trait ModeledHeader extends HttpHeader with Serializable {
  def name: String = companion.name
  def value: String = renderValue(new StringRendering).get
  def lowercaseName: String = companion.lowercaseName
  def render[R <: Rendering](r: R): r.type = renderValue(r ~~ companion)
  def renderValue[R <: Rendering](r: R): r.type
  protected def companion: ModeledCompanion
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-6.1
object Connection extends ModeledCompanion {
  def apply(first: String, more: String*): Connection = apply(immutable.Seq(first +: more: _*))
  implicit val tokensRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class Connection(tokens: immutable.Seq[String]) extends ModeledHeader {
  require(tokens.nonEmpty, "tokens must not be empty")
  import Connection.tokensRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ tokens
  def hasClose = has("close")
  def hasKeepAlive = has("keep-alive")
  @tailrec private def has(item: String, ix: Int = 0): Boolean =
    if (ix < tokens.length)
      if (tokens(ix) equalsIgnoreCase item) true
      else has(item, ix + 1)
    else false
  protected def companion = Connection
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-3.3.2
object `Content-Length` extends ModeledCompanion
final case class `Content-Length`(length: Long)(implicit ev: ProtectedHeaderCreation.Enabled) extends ModeledHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ length
  protected def companion = `Content-Length`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.1.1
object Expect extends ModeledCompanion {
  val `100-continue` = new Expect() {}
}
sealed abstract case class Expect private () extends ModeledHeader {
  def renderValue[R <: Rendering](r: R): r.type = r ~~ "100-continue"
  protected def companion = Expect
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-5.4
object Host extends ModeledCompanion {
  def apply(address: InetSocketAddress): Host = apply(address.getHostName, address.getPort) // TODO: upgrade to `getHostString` once we are on JDK7
  def apply(host: String): Host = apply(host, 0)
  def apply(host: String, port: Int): Host = apply(Uri.Host(host), port)
  val empty = Host("")
}
final case class Host(host: Uri.Host, port: Int = 0) extends japi.headers.Host with ModeledHeader {
  import UriRendering.HostRenderer
  require((port >> 16) == 0, "Illegal port: " + port)
  def isEmpty = host.isEmpty
  def renderValue[R <: Rendering](r: R): r.type = if (port > 0) r ~~ host ~~ ':' ~~ port else r ~~ host
  protected def companion = Host
  def equalsIgnoreCase(other: Host): Boolean = host.equalsIgnoreCase(other.host) && port == other.port
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.5
// http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-3.2
object `If-Range` extends ModeledCompanion {
  def apply(tag: EntityTag): `If-Range` = apply(Left(tag))
  def apply(timestamp: DateTime): `If-Range` = apply(Right(timestamp))
}
final case class `If-Range`(entityTagOrDateTime: Either[EntityTag, DateTime]) extends ModeledHeader {
  def renderValue[R <: Rendering](r: R): r.type =
    entityTagOrDateTime match {
      case Left(tag)       ⇒ r ~~ tag
      case Right(dateTime) ⇒ dateTime.renderRfc1123DateTimeString(r)
    }
  protected def companion = `If-Range`
}

// FIXME: resurrect SSL-Session-Info header once akka.io.SslTlsSupport supports it
final case class RawHeader(name: String, value: String) extends japi.headers.RawHeader {
  val lowercaseName = name.toLowerCase
  def render[R <: Rendering](r: R): r.type = r ~~ name ~~ ':' ~~ ' ' ~~ value
}

import japi.JavaMapping.Implicits._

// AUTO-GENERATED

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.2
object Accept extends ModeledCompanion {
  def apply(mediaRanges: MediaRange*): Accept = apply(immutable.Seq(mediaRanges: _*))
  implicit val mediaRangesRenderer = Renderer.defaultSeqRenderer[MediaRange] // cache
}
final case class Accept(mediaRanges: immutable.Seq[MediaRange]) extends japi.headers.Accept with ModeledHeader {
  import Accept.mediaRangesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ mediaRanges
  protected def companion = Accept

  /** Java API */
  def getMediaRanges = mediaRanges.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.3
object `Accept-Charset` extends ModeledCompanion {
  def apply(charsetRanges: HttpCharsetRange*): `Accept-Charset` = apply(immutable.Seq(charsetRanges: _*))
  implicit val charsetRangesRenderer = Renderer.defaultSeqRenderer[HttpCharsetRange] // cache
}
final case class `Accept-Charset`(charsetRanges: immutable.Seq[HttpCharsetRange]) extends japi.headers.AcceptCharset with ModeledHeader {
  import `Accept-Charset`.charsetRangesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ charsetRanges
  protected def companion = `Accept-Charset`

  /** Java API */
  def getCharsetRanges = charsetRanges.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.4
object `Accept-Encoding` extends ModeledCompanion {
  def apply(encodings: HttpEncodingRange*): `Accept-Encoding` = apply(immutable.Seq(encodings: _*))
  implicit val encodingsRenderer = Renderer.defaultSeqRenderer[HttpEncodingRange] // cache
}
final case class `Accept-Encoding`(encodings: immutable.Seq[HttpEncodingRange]) extends japi.headers.AcceptEncoding with ModeledHeader {
  import `Accept-Encoding`.encodingsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Accept-Encoding`

  /** Java API */
  def getEncodings = encodings.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.5
object `Accept-Language` extends ModeledCompanion {
  def apply(languages: LanguageRange*): `Accept-Language` = apply(immutable.Seq(languages: _*))
  implicit val languagesRenderer = Renderer.defaultSeqRenderer[LanguageRange] // cache
}
final case class `Accept-Language`(languages: immutable.Seq[LanguageRange]) extends japi.headers.AcceptLanguage with ModeledHeader {
  import `Accept-Language`.languagesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ languages
  protected def companion = `Accept-Language`

  /** Java API */
  def getLanguages = languages.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-2.3
object `Accept-Ranges` extends ModeledCompanion {
  def apply(rangeUnits: RangeUnit*): `Accept-Ranges` = apply(immutable.Seq(rangeUnits: _*))
  implicit val rangeUnitsRenderer = Renderer.defaultSeqRenderer[RangeUnit] // cache
}
final case class `Accept-Ranges`(rangeUnits: immutable.Seq[RangeUnit]) extends japi.headers.AcceptRanges with ModeledHeader {
  import `Accept-Ranges`.rangeUnitsRenderer
  def renderValue[R <: Rendering](r: R): r.type = if (rangeUnits.isEmpty) r ~~ "none" else r ~~ rangeUnits
  protected def companion = `Accept-Ranges`

  /** Java API */
  def getRangeUnits = rangeUnits.asJava
}

// http://www.w3.org/TR/cors/#access-control-allow-credentials-response-header
object `Access-Control-Allow-Credentials` extends ModeledCompanion
final case class `Access-Control-Allow-Credentials`(allow: Boolean) extends japi.headers.AccessControlAllowCredentials with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ allow.toString
  protected def companion = `Access-Control-Allow-Credentials`
}

// http://www.w3.org/TR/cors/#access-control-allow-headers-response-header
object `Access-Control-Allow-Headers` extends ModeledCompanion {
  def apply(headers: String*): `Access-Control-Allow-Headers` = apply(immutable.Seq(headers: _*))
  implicit val headersRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class `Access-Control-Allow-Headers`(headers: immutable.Seq[String]) extends japi.headers.AccessControlAllowHeaders with ModeledHeader {
  import `Access-Control-Allow-Headers`.headersRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Allow-Headers`

  /** Java API */
  def getHeaders = headers.asJava
}

// http://www.w3.org/TR/cors/#access-control-allow-methods-response-header
object `Access-Control-Allow-Methods` extends ModeledCompanion {
  def apply(methods: HttpMethod*): `Access-Control-Allow-Methods` = apply(immutable.Seq(methods: _*))
  implicit val methodsRenderer = Renderer.defaultSeqRenderer[HttpMethod] // cache
}
final case class `Access-Control-Allow-Methods`(methods: immutable.Seq[HttpMethod]) extends japi.headers.AccessControlAllowMethods with ModeledHeader {
  import `Access-Control-Allow-Methods`.methodsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ methods
  protected def companion = `Access-Control-Allow-Methods`

  /** Java API */
  def getMethods = methods.asJava
}

// http://www.w3.org/TR/cors/#access-control-allow-origin-response-header
object `Access-Control-Allow-Origin` extends ModeledCompanion
final case class `Access-Control-Allow-Origin`(range: HttpOriginRange) extends japi.headers.AccessControlAllowOrigin with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ range
  protected def companion = `Access-Control-Allow-Origin`
}

// http://www.w3.org/TR/cors/#access-control-expose-headers-response-header
object `Access-Control-Expose-Headers` extends ModeledCompanion {
  def apply(headers: String*): `Access-Control-Expose-Headers` = apply(immutable.Seq(headers: _*))
  implicit val headersRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class `Access-Control-Expose-Headers`(headers: immutable.Seq[String]) extends japi.headers.AccessControlExposeHeaders with ModeledHeader {
  import `Access-Control-Expose-Headers`.headersRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Expose-Headers`

  /** Java API */
  def getHeaders = headers.asJava
}

// http://www.w3.org/TR/cors/#access-control-max-age-response-header
object `Access-Control-Max-Age` extends ModeledCompanion
final case class `Access-Control-Max-Age`(deltaSeconds: Long) extends japi.headers.AccessControlMaxAge with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ deltaSeconds
  protected def companion = `Access-Control-Max-Age`
}

// http://www.w3.org/TR/cors/#access-control-request-headers-request-header
object `Access-Control-Request-Headers` extends ModeledCompanion {
  def apply(headers: String*): `Access-Control-Request-Headers` = apply(immutable.Seq(headers: _*))
  implicit val headersRenderer = Renderer.defaultSeqRenderer[String] // cache
}
final case class `Access-Control-Request-Headers`(headers: immutable.Seq[String]) extends japi.headers.AccessControlRequestHeaders with ModeledHeader {
  import `Access-Control-Request-Headers`.headersRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ headers
  protected def companion = `Access-Control-Request-Headers`

  /** Java API */
  def getHeaders = headers.asJava
}

// http://www.w3.org/TR/cors/#access-control-request-method-request-header
object `Access-Control-Request-Method` extends ModeledCompanion
final case class `Access-Control-Request-Method`(method: HttpMethod) extends japi.headers.AccessControlRequestMethod with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ method
  protected def companion = `Access-Control-Request-Method`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.4.1
object Allow extends ModeledCompanion {
  def apply(methods: HttpMethod*): Allow = apply(immutable.Seq(methods: _*))
  implicit val methodsRenderer = Renderer.defaultSeqRenderer[HttpMethod] // cache
}
final case class Allow(methods: immutable.Seq[HttpMethod]) extends japi.headers.Allow with ModeledHeader {
  import Allow.methodsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ methods
  protected def companion = Allow

  /** Java API */
  def getMethods = methods.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.2
object Authorization extends ModeledCompanion
final case class Authorization(credentials: HttpCredentials) extends japi.headers.Authorization with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ credentials
  protected def companion = Authorization
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2
object `Cache-Control` extends ModeledCompanion {
  def apply(directives: CacheDirective*): `Cache-Control` = apply(immutable.Seq(directives: _*))
  implicit val directivesRenderer = Renderer.defaultSeqRenderer[CacheDirective] // cache
}
final case class `Cache-Control`(directives: immutable.Seq[CacheDirective]) extends japi.headers.CacheControl with ModeledHeader {
  import `Cache-Control`.directivesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ directives
  protected def companion = `Cache-Control`

  /** Java API */
  def getDirectives = directives.asJava
}

// http://tools.ietf.org/html/rfc6266
object `Content-Disposition` extends ModeledCompanion
final case class `Content-Disposition`(dispositionType: ContentDispositionType, params: Map[String, String] = Map.empty) extends japi.headers.ContentDisposition with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = { r ~~ dispositionType; params foreach { case (k, v) ⇒ r ~~ "; " ~~ k ~~ '=' ~~# v }; r }
  protected def companion = `Content-Disposition`

  /** Java API */
  def getParams = params.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-3.1.2.2
object `Content-Encoding` extends ModeledCompanion {
  def apply(encodings: HttpEncoding*): `Content-Encoding` = apply(immutable.Seq(encodings: _*))
  implicit val encodingsRenderer = Renderer.defaultSeqRenderer[HttpEncoding] // cache
}
final case class `Content-Encoding`(encodings: immutable.Seq[HttpEncoding]) extends japi.headers.ContentEncoding with ModeledHeader {
  import `Content-Encoding`.encodingsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Content-Encoding`

  /** Java API */
  def getEncodings = encodings.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-4.2
object `Content-Range` extends ModeledCompanion {
  def apply(byteContentRange: ByteContentRange): `Content-Range` = apply(RangeUnits.Bytes, byteContentRange)

}
final case class `Content-Range`(rangeUnit: RangeUnit, contentRange: ContentRange) extends japi.headers.ContentRange with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ rangeUnit ~~ ' ' ~~ contentRange
  protected def companion = `Content-Range`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-3.1.1.5
object `Content-Type` extends ModeledCompanion
final case class `Content-Type`(contentType: ContentType) extends japi.headers.ContentType with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ contentType
  protected def companion = `Content-Type`
}

// https://tools.ietf.org/html/rfc6265#section-4.2
object Cookie extends ModeledCompanion {
  implicit val cookieNameValueOnlyRenderer: Renderer[HttpCookie] = new Renderer[HttpCookie] {
    def render[R <: Rendering](r: R, c: HttpCookie): r.type = r ~~ c.name ~~ '=' ~~ c.content
  }

  def apply(cookies: HttpCookie*): Cookie = apply(immutable.Seq(cookies: _*))
  implicit val cookiesRenderer = Renderer.seqRenderer[HttpCookie](separator = "; ") // cache
}
final case class Cookie(cookies: immutable.Seq[HttpCookie]) extends japi.headers.Cookie with ModeledHeader {
  import Cookie.cookiesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ cookies
  protected def companion = Cookie

  /** Java API */
  def getCookies = cookies.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.1.1.2
object Date extends ModeledCompanion
final case class Date(date: DateTime) extends japi.headers.Date with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = Date
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-2.3
object ETag extends ModeledCompanion {
  def apply(tag: String, weak: Boolean = false): ETag = ETag(EntityTag(tag, weak))

}
final case class ETag(etag: EntityTag) extends japi.headers.ETag with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ etag
  protected def companion = ETag
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.1
object `If-Match` extends ModeledCompanion {
  val `*` = `If-Match`(EntityTagRange.`*`)
  def apply(first: EntityTag, more: EntityTag*): `If-Match` =
    `If-Match`(EntityTagRange(first +: more: _*))

}
final case class `If-Match`(m: EntityTagRange) extends japi.headers.IfMatch with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ m
  protected def companion = `If-Match`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.3
object `If-Modified-Since` extends ModeledCompanion
final case class `If-Modified-Since`(date: DateTime) extends japi.headers.IfModifiedSince with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `If-Modified-Since`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.2
object `If-None-Match` extends ModeledCompanion {
  val `*` = `If-None-Match`(EntityTagRange.`*`)
  def apply(first: EntityTag, more: EntityTag*): `If-None-Match` =
    `If-None-Match`(EntityTagRange(first +: more: _*))

}
final case class `If-None-Match`(m: EntityTagRange) extends japi.headers.IfNoneMatch with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ m
  protected def companion = `If-None-Match`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.4
object `If-Unmodified-Since` extends ModeledCompanion
final case class `If-Unmodified-Since`(date: DateTime) extends japi.headers.IfUnmodifiedSince with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `If-Unmodified-Since`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-2.2
object `Last-Modified` extends ModeledCompanion
final case class `Last-Modified`(date: DateTime) extends japi.headers.LastModified with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = date.renderRfc1123DateTimeString(r)
  protected def companion = `Last-Modified`
}

// http://tools.ietf.org/html/rfc5988#section-5
object Link extends ModeledCompanion {
  def apply(uri: Uri, first: LinkParam, more: LinkParam*): Link = apply(immutable.Seq(LinkValue(uri, first +: more: _*)))

  def apply(values: LinkValue*): Link = apply(immutable.Seq(values: _*))
  implicit val valuesRenderer = Renderer.defaultSeqRenderer[LinkValue] // cache
}
final case class Link(values: immutable.Seq[LinkValue]) extends japi.headers.Link with ModeledHeader {
  import Link.valuesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ values
  protected def companion = Link

  /** Java API */
  def getValues = values.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.1.2
object Location extends ModeledCompanion
final case class Location(uri: Uri) extends japi.headers.Location with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = { import UriRendering.UriRenderer; r ~~ uri }
  protected def companion = Location

  /** Java API */
  def getUri = uri.asJava
}

// http://tools.ietf.org/html/rfc6454#section-7
object Origin extends ModeledCompanion {
  def apply(origins: HttpOrigin*): Origin = apply(immutable.Seq(origins: _*))
}
final case class Origin(origins: immutable.Seq[HttpOrigin]) extends japi.headers.Origin with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ origins
  protected def companion = Origin

  /** Java API */
  def getOrigins = origins.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.3
object `Proxy-Authenticate` extends ModeledCompanion {
  def apply(challenges: HttpChallenge*): `Proxy-Authenticate` = apply(immutable.Seq(challenges: _*))
  implicit val challengesRenderer = Renderer.defaultSeqRenderer[HttpChallenge] // cache
}
final case class `Proxy-Authenticate`(challenges: immutable.Seq[HttpChallenge]) extends japi.headers.ProxyAuthenticate with ModeledHeader {
  import `Proxy-Authenticate`.challengesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ challenges
  protected def companion = `Proxy-Authenticate`

  /** Java API */
  def getChallenges = challenges.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.4
object `Proxy-Authorization` extends ModeledCompanion
final case class `Proxy-Authorization`(credentials: HttpCredentials) extends japi.headers.ProxyAuthorization with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ credentials
  protected def companion = `Proxy-Authorization`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p5-range-26#section-3.1
object Range extends ModeledCompanion {
  def apply(first: ByteRange, more: ByteRange*): Range = apply(immutable.Seq(first +: more: _*))
  def apply(ranges: immutable.Seq[ByteRange]): Range = Range(RangeUnits.Bytes, ranges)

  implicit val rangesRenderer = Renderer.defaultSeqRenderer[ByteRange] // cache
}
final case class Range(rangeUnit: RangeUnit, ranges: immutable.Seq[ByteRange]) extends japi.headers.Range with ModeledHeader {
  import Range.rangesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ rangeUnit ~~ '=' ~~ ranges
  protected def companion = Range

  /** Java API */
  def getRanges = ranges.asJava
}

object `Raw-Request-URI` extends ModeledCompanion
final case class `Raw-Request-URI`(uri: String) extends japi.headers.RawRequestURI with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ uri
  protected def companion = `Raw-Request-URI`
}

object `Remote-Address` extends ModeledCompanion
final case class `Remote-Address`(address: RemoteAddress) extends japi.headers.RemoteAddress with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ address
  protected def companion = `Remote-Address`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.4.2
object Server extends ModeledCompanion {
  def apply(products: String): Server = apply(ProductVersion.parseMultiple(products))

  def apply(products: ProductVersion*): Server = apply(immutable.Seq(products: _*))
  implicit val productsRenderer = Renderer.seqRenderer[ProductVersion](separator = " ") // cache
}
final case class Server(products: immutable.Seq[ProductVersion]) extends japi.headers.Server with ModeledHeader {
  import Server.productsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ products
  protected def companion = Server

  /** Java API */
  def getProducts = products.asJava
}

// https://tools.ietf.org/html/rfc6265
object `Set-Cookie` extends ModeledCompanion
final case class `Set-Cookie`(cookie: HttpCookie) extends japi.headers.SetCookie with ModeledHeader {

  def renderValue[R <: Rendering](r: R): r.type = r ~~ cookie
  protected def companion = `Set-Cookie`
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-3.3.1
object `Transfer-Encoding` extends ModeledCompanion {
  def apply(encodings: TransferEncoding*): `Transfer-Encoding` = apply(immutable.Seq(encodings: _*))
  implicit val encodingsRenderer = Renderer.defaultSeqRenderer[TransferEncoding] // cache
}
final case class `Transfer-Encoding`(encodings: immutable.Seq[TransferEncoding]) extends japi.headers.TransferEncoding with ModeledHeader {
  import `Transfer-Encoding`.encodingsRenderer
  def hasChunked: Boolean = encodings contains TransferEncodings.chunked
  def renderValue[R <: Rendering](r: R): r.type = r ~~ encodings
  protected def companion = `Transfer-Encoding`

  /** Java API */
  def getEncodings = encodings.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.5.3
object `User-Agent` extends ModeledCompanion {
  def apply(products: String): `User-Agent` = apply(ProductVersion.parseMultiple(products))

  def apply(products: ProductVersion*): `User-Agent` = apply(immutable.Seq(products: _*))
  implicit val productsRenderer = Renderer.seqRenderer[ProductVersion](separator = " ") // cache
}
final case class `User-Agent`(products: immutable.Seq[ProductVersion]) extends japi.headers.UserAgent with ModeledHeader {
  import `User-Agent`.productsRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ products
  protected def companion = `User-Agent`

  /** Java API */
  def getProducts = products.asJava
}

// http://tools.ietf.org/html/draft-ietf-httpbis-p7-auth-26#section-4.1
object `WWW-Authenticate` extends ModeledCompanion {
  def apply(challenges: HttpChallenge*): `WWW-Authenticate` = apply(immutable.Seq(challenges: _*))
  implicit val challengesRenderer = Renderer.defaultSeqRenderer[HttpChallenge] // cache
}
final case class `WWW-Authenticate`(challenges: immutable.Seq[HttpChallenge]) extends japi.headers.WWWAuthenticate with ModeledHeader {
  import `WWW-Authenticate`.challengesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ challenges
  protected def companion = `WWW-Authenticate`

  /** Java API */
  def getChallenges = challenges.asJava
}

// http://en.wikipedia.org/wiki/X-Forwarded-For
object `X-Forwarded-For` extends ModeledCompanion {
  def apply(first: String, more: String*): `X-Forwarded-For` =
    apply(immutable.Seq((first +: more).map(RemoteAddress.apply): _*))

  def apply(addresses: RemoteAddress*): `X-Forwarded-For` = apply(immutable.Seq(addresses: _*))
  implicit val addressesRenderer = Renderer.defaultSeqRenderer[RemoteAddress] // cache
}
final case class `X-Forwarded-For`(addresses: immutable.Seq[RemoteAddress]) extends japi.headers.XForwardedFor with ModeledHeader {
  import `X-Forwarded-For`.addressesRenderer
  def renderValue[R <: Rendering](r: R): r.type = r ~~ addresses
  protected def companion = `X-Forwarded-For`

  /** Java API */
  def getAddresses = addresses.asJava
}

