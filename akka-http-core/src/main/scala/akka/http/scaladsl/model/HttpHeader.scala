/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import scala.util.{ Success, Failure }
import akka.parboiled2.ParseError
import akka.http.impl.util.ToStringRenderable
import akka.http.impl.model.parser.{ CharacterClasses, HeaderParser }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.model.headers._

/**
 * The model of an HTTP header. In its most basic form headers are simple name-value pairs. Header names
 * are compared in a case-insensitive way.
 */
abstract class HttpHeader extends jm.HttpHeader with ToStringRenderable {
  def name: String
  def value: String
  def lowercaseName: String
  def is(nameInLowerCase: String): Boolean = lowercaseName == nameInLowerCase
  def isNot(nameInLowerCase: String): Boolean = lowercaseName != nameInLowerCase
}

object HttpHeader {
  /**
   * Extract name and value from a header.
   * CAUTION: The name must be matched in *all-lowercase*!.
   */
  def unapply(header: HttpHeader): Option[(String, String)] = Some((header.lowercaseName, header.value))

  /**
   * Attempts to parse the given header name and value string into a header model instance.
   *
   * This process has several possible outcomes:
   *
   * 1. The header name corresponds to a properly modelled header and
   *    a) the value is valid for this header type.
   *       In this case the method returns a `ParsingResult.Ok` with the respective header instance and no errors.
   *    b) the value consists of a number elements, some of which valid and some invalid, and the header type supports
   *       partial value parsing. In this case the method returns a `ParsingResult.Ok` with the respective header
   *       instance holding the valid value elements and an [[ErrorInfo]] for each invalid value.
   *    c) the value has invalid elements and the header type doesn't support partial value parsing.
   *       In this case the method returns a `ParsingResult.Ok` with a [[akka.http.scaladsl.model.headers.RawHeader]] instance and
   *       a single [[ErrorInfo]] for the value parsing problem.
   *
   * 2. The header name does not correspond to a properly modelled header but the header name and the value are both
   *    syntactically legal according to the basic header requirements from the HTTP specification.
   *    (http://tools.ietf.org/html/rfc7230#section-3.2)
   *    In this case the method returns a `ParsingResult.Ok` with a [[akka.http.scaladsl.model.headers.RawHeader]] instance and no errors.
   *
   * 3. The header name or value are illegal according to the basic requirements for HTTP headers
   *    (http://tools.ietf.org/html/rfc7230#section-3.2). In this case the method returns a `ParsingResult.Error`.
   */
  def parse(name: String, value: String, settings: HeaderParser.Settings = HeaderParser.DefaultSettings): ParsingResult =
    if (name.forall(CharacterClasses.tchar)) {
      import akka.parboiled2.Parser.DeliveryScheme.Try
      val parser = new HeaderParser(value, settings)
      parser.`header-field-value`.run() match {
        case Success(preProcessedValue) ⇒
          try {
            HeaderParser.parseFull(name.toLowerCase, preProcessedValue, settings) match {
              case Right(header) ⇒ ParsingResult.Ok(header, Nil)
              case Left(info) ⇒
                val errors = info.withSummaryPrepended(s"Illegal HTTP header '$name'") :: Nil
                ParsingResult.Ok(RawHeader(name, preProcessedValue), errors)
            }
          } catch {
            case HeaderParser.RuleNotFoundException ⇒ ParsingResult.Ok(RawHeader(name, preProcessedValue), Nil)
          }
        case Failure(error) ⇒
          val info = error match {
            case e: ParseError ⇒ parser.parseError(e)
            case e             ⇒ parser.failure(e)
          }
          ParsingResult.Error(info.left.get.withSummaryPrepended(s"Illegal HTTP header value"))
      }
    } else ParsingResult.Error(ErrorInfo(s"Illegal HTTP header name", name))

  sealed trait ParsingResult {
    def errors: List[ErrorInfo]
  }

  object ParsingResult {
    /**
     * The parsing run produced a result. If there were parsing errors (which did not prevent the run from
     * completing) they are reported in the given error list.
     */
    final case class Ok(header: HttpHeader, errors: List[ErrorInfo]) extends ParsingResult

    /**
     * The parsing run failed due to a fatal parsing error.
     */
    final case class Error(error: ErrorInfo) extends ParsingResult { def errors = error :: Nil }
  }
}
