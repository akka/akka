/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import scala.annotation.tailrec
import scala.util.{ Failure, Success }
import org.parboiled2._
import akka.http.model.{ ErrorInfo, HttpHeader }

private[http] class HeaderParser(val input: ParserInput) extends Parser
  with CommonRules
  with AcceptCharsetHeader
  with IpAddressParsing
  with CommonActions
  with StringBuilding {

  def errorInfo(e: ParseError) =
    ErrorInfo("Illegal HTTP header: " + formatError(e, showLine = false), formatErrorLine(e))
}

object HeaderParser {

  @tailrec def parseHeaders(headers: List[HttpHeader], errors: List[ErrorInfo] = Nil,
                            result: List[HttpHeader] = Nil): (List[ErrorInfo], List[HttpHeader]) =
    headers match {
      case Nil ⇒ errors -> result
      case head :: tail ⇒ parseHeader(head) match {
        case Right(h)    ⇒ parseHeaders(tail, errors, h :: result)
        case Left(error) ⇒ parseHeaders(tail, error :: errors, head :: result)
      }
    }

  import shapeless._

  private val ruleDispatch = DynamicRuleDispatch[HeaderParser, HttpHeader :: HNil](
    "accept-charset")

  def parseHeader(header: HttpHeader): Either[ErrorInfo, HttpHeader] =
    header match {
      case x @ headers.RawHeader(name, value) ⇒
        ruleDispatch(new HeaderParser(value), x.lowercaseName) match {
          case Some(rule) ⇒ parse(rule, value) match {
            case x @ Right(_) ⇒ x
            case Left(info)   ⇒ Left(info.withSummaryPrepended("Illegal HTTP header '" + name + '\''))
          }
          case None ⇒ Right(x) // if we don't have a rule for the header we leave it unparsed
        }
      case x ⇒ Right(x) // already parsed
    }

  def parse(rule: RunnableRule[HeaderParser, HttpHeader :: HNil], input: String): Either[ErrorInfo, HttpHeader] =
    rule.run() match {
      case Success(x)                      ⇒ Right(x)
      case Failure(e: ParseError)          ⇒ Left(rule.parserInstance.errorInfo(e))
      case Failure(e: IllegalUriException) ⇒ Left(e.info)
      case Failure(e)                      ⇒ Left(ErrorInfo.fromCompoundString(e.getMessage))
    }
}