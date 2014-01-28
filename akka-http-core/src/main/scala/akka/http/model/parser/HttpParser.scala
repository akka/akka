/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.parser

import akka.http.model.{ ErrorInfo, HttpHeader }
import org.parboiled2._

private[http] class HttpParser(val input: ParserInput) extends Parser
  with BasicRules
  with IpAddressParsing
  with StringBuilding

object HttpParser {
  def parseHeader(header: HttpHeader): Either[ErrorInfo, HttpHeader] = ???
}