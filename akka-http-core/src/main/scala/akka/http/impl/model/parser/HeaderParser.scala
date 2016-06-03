/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.settings.ParserSettings.CookieParsingMode
import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.stream.impl.ConstantFun
import scala.util.control.NonFatal
import akka.http.impl.util.SingletonException
import akka.parboiled2._
import akka.shapeless._
import akka.http.scaladsl.model._

/**
 * INTERNAL API.
 */
private[http] class HeaderParser(
  val input: ParserInput,
  settings:  HeaderParser.Settings = HeaderParser.DefaultSettings)
  extends Parser with DynamicRuleHandler[HeaderParser, HttpHeader :: HNil]
  with CommonRules
  with AcceptCharsetHeader
  with AcceptEncodingHeader
  with AcceptHeader
  with AcceptLanguageHeader
  with CacheControlHeader
  with ContentDispositionHeader
  with ContentTypeHeader
  with CommonActions
  with IpAddressParsing
  with LinkHeader
  with SimpleHeaders
  with StringBuilding
  with WebSocketHeaders {
  import CharacterClasses._

  override def customMediaTypes = settings.customMediaTypes

  // http://www.rfc-editor.org/errata_search.php?rfc=7230 errata id 4189
  def `header-field-value`: Rule1[String] = rule {
    FWS ~ clearSB() ~ `field-value` ~ FWS ~ EOI ~ push(sb.toString)
  }
  def `field-value` = {
    var fwsStart = cursor
    rule {
      zeroOrMore(`field-value-chunk`).separatedBy { // zeroOrMore because we need to also accept empty values
        run { fwsStart = cursor } ~ FWS ~ &(`field-value-char`) ~ run { if (cursor > fwsStart) sb.append(' ') }
      }
    }
  }
  def `field-value-chunk` = rule { oneOrMore(`field-value-char` ~ appendSB()) }
  def `field-value-char` = rule { VCHAR | `obs-text` }
  def FWS = rule { zeroOrMore(WSP) ~ zeroOrMore(`obs-fold`) }
  def `obs-fold` = rule { CRLF ~ oneOrMore(WSP) }

  ///////////////// DynamicRuleHandler //////////////

  type Result = Either[ErrorInfo, HttpHeader]
  def parser: HeaderParser = this
  def success(result: HttpHeader :: HNil): Result = Right(result.head)
  def parseError(error: ParseError): Result = {
    val formatter = new ErrorFormatter(showLine = false)
    Left(ErrorInfo(formatter.format(error, input), formatter.formatErrorLine(error, input)))
  }
  def failure(error: Throwable): Result = error match {
    case IllegalUriException(info) ⇒ Left(info)
    case NonFatal(e)               ⇒ Left(ErrorInfo.fromCompoundString(e.getMessage))
  }
  def ruleNotFound(ruleName: String): Result = throw HeaderParser.RuleNotFoundException

  def newUriParser(input: ParserInput): UriParser = new UriParser(input, uriParsingMode = settings.uriParsingMode)

  def `cookie-value`: Rule1[String] =
    settings.cookieParsingMode match {
      case CookieParsingMode.RFC6265 ⇒ rule { `cookie-value-rfc-6265` }
      case CookieParsingMode.Raw     ⇒ rule { `cookie-value-raw` }
    }

  def createCookiePair(name: String, value: String): HttpCookiePair = settings.cookieParsingMode match {
    case CookieParsingMode.RFC6265 ⇒ HttpCookiePair(name, value)
    case CookieParsingMode.Raw     ⇒ HttpCookiePair.raw(name, value)
  }
}

/**
 * INTERNAL API.
 */
private[http] object HeaderParser {
  object RuleNotFoundException extends SingletonException
  object EmptyCookieException extends SingletonException("Cookie header contained no parsable cookie values.")

  def parseFull(headerName: String, value: String, settings: Settings = DefaultSettings): HeaderParser#Result = {
    import akka.parboiled2.EOI
    val v = value + EOI // this makes sure the parser isn't broken even if there's no trailing garbage in this value
    val parser = new HeaderParser(v, settings)
    dispatch(parser, headerName) match {
      case r @ Right(_) if parser.cursor == v.length ⇒ r
      case r @ Right(_) ⇒
        Left(ErrorInfo(
          "Header parsing error",
          s"Rule for $headerName accepted trailing garbage. Is the parser missing a trailing EOI?"))
      case Left(e) ⇒ Left(e.copy(summary = e.summary.filterNot(_ == EOI), detail = e.detail.filterNot(_ == EOI)))
    }
  }

  val (dispatch, ruleNames) = DynamicRuleDispatch[HeaderParser, HttpHeader :: HNil](
    "accept",
    "accept-charset",
    "accept-encoding",
    "accept-language",
    "accept-ranges",
    "access-control-allow-credentials",
    "access-control-allow-headers",
    "access-control-allow-methods",
    "access-control-allow-origin",
    "access-control-expose-headers",
    "access-control-max-age",
    "access-control-request-headers",
    "access-control-request-method",
    "accept",
    "age",
    "allow",
    "authorization",
    "cache-control",
    "connection",
    "content-disposition",
    "content-encoding",
    "content-length",
    "content-range",
    "content-type",
    "cookie",
    "date",
    "etag",
    "expect",
    "expires",
    "host",
    "if-match",
    "if-modified-since",
    "if-none-match",
    "if-range",
    "if-unmodified-since",
    "last-modified",
    "link",
    "location",
    "origin",
    "proxy-authenticate",
    "proxy-authorization",
    "range",
    "referer",
    "server",
    "sec-websocket-accept",
    "sec-websocket-extensions",
    "sec-websocket-key",
    "sec-websocket-protocol",
    "sec-websocket-version",
    "set-cookie",
    "strict-transport-security",
    "transfer-encoding",
    "upgrade",
    "user-agent",
    "www-authenticate",
    "x-forwarded-for",
    "x-real-ip")

  abstract class Settings {
    def uriParsingMode: Uri.ParsingMode
    def cookieParsingMode: ParserSettings.CookieParsingMode
    def customMediaTypes: MediaTypes.FindCustom
  }
  def Settings(
    uriParsingMode:    Uri.ParsingMode                  = Uri.ParsingMode.Relaxed,
    cookieParsingMode: ParserSettings.CookieParsingMode = ParserSettings.CookieParsingMode.RFC6265,
    customMediaTypes:  MediaTypes.FindCustom            = ConstantFun.scalaAnyTwoToNone): Settings = {
    val _uriParsingMode = uriParsingMode
    val _cookieParsingMode = cookieParsingMode
    val _customMediaTypes = customMediaTypes

    new Settings {
      def uriParsingMode: Uri.ParsingMode = _uriParsingMode
      def cookieParsingMode: CookieParsingMode = _cookieParsingMode
      def customMediaTypes: MediaTypes.FindCustom = _customMediaTypes
    }
  }
  val DefaultSettings: Settings = Settings()
}