/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import scala.util.control.NonFatal
import akka.http.util.SingletonException
import akka.parboiled2._
import akka.shapeless._

/**
 * INTERNAL API.
 */
private[http] class HeaderParser(val input: ParserInput) extends Parser with DynamicRuleHandler[HeaderParser, HttpHeader :: HNil]
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
  with WebsocketHeaders {
  import CharacterClasses._

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
  def parseError(error: ParseError): Result =
    Left(ErrorInfo(formatError(error, showLine = false), formatErrorLine(error)))
  def failure(error: Throwable): Result = error match {
    case IllegalUriException(info) ⇒ Left(info)
    case NonFatal(e)               ⇒ Left(ErrorInfo.fromCompoundString(e.getMessage))
  }
  def ruleNotFound(ruleName: String): Result = throw HeaderParser.RuleNotFoundException
}

/**
 * INTERNAL API.
 */
private[http] object HeaderParser {
  object RuleNotFoundException extends SingletonException

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
    "transfer-encoding",
    "user-agent",
    "www-authenticate",
    "x-forwarded-for")
}