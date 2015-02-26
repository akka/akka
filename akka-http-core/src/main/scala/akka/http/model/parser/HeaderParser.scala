/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import akka.http.util.SingletonException
import akka.http.model.{ ErrorInfo, HttpHeader }
import akka.parboiled2._
import akka.shapeless._

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
  with StringBuilding {

  def errorInfo(e: ParseError) = ErrorInfo(formatError(e, showLine = false), formatErrorLine(e))

  ///////////////// DynamicRuleHandler //////////////

  type Result = Either[ErrorInfo, HttpHeader]
  def parser: HeaderParser = this
  def success(result: HttpHeader :: HNil): Result = Right(result.head)
  def parseError(error: ParseError): Result = Left(errorInfo(error))
  def failure(error: Throwable): Result = error match {
    case IllegalUriException(info) ⇒ Left(info)
    case NonFatal(e)               ⇒ Left(ErrorInfo.fromCompoundString(e.getMessage))
  }
  def ruleNotFound(ruleName: String): Result = throw HeaderParser.RuleNotFoundException
}

object HeaderParser {
  object RuleNotFoundException extends SingletonException

  @tailrec def parseHeaders(headers: List[HttpHeader], errors: List[ErrorInfo] = Nil,
                            result: List[HttpHeader] = Nil): (List[ErrorInfo], List[HttpHeader]) =
    headers match {
      case Nil ⇒ errors -> result
      case immutable.::(head, tail) ⇒ parseHeader(head) match {
        case Right(h)    ⇒ parseHeaders(tail, errors, h :: result)
        case Left(error) ⇒ parseHeaders(tail, error :: errors, head :: result)
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
    "set-cookie",
    "transfer-encoding",
    "user-agent",
    "www-authenticate",
    "x-forwarded-for")

  def parseHeader(header: HttpHeader): Either[ErrorInfo, HttpHeader] =
    header match {
      case rawHeader @ headers.RawHeader(name, value) ⇒
        try {
          dispatch(new HeaderParser(value), rawHeader.lowercaseName) match {
            case x @ Right(_) ⇒ x
            case Left(info)   ⇒ Left(info.withSummaryPrepended(s"Illegal HTTP header '$name'"))
          }
        } catch {
          case RuleNotFoundException ⇒ Right(rawHeader) // if we don't have a rule for the header we leave it unparsed
        }
      case x ⇒ Right(x) // already parsed
    }
}