/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.http.scaladsl.model.headers._
import akka.parboiled2._

// see grammar at http://tools.ietf.org/html/rfc6455#section-4.3
private[parser] trait WebSocketHeaders { this: Parser with CommonRules with CommonActions ⇒
  import CharacterClasses._
  import Base64Parsing.rfc2045Alphabet

  def `sec-websocket-accept` = rule {
    `base64-value-non-empty` ~ EOI ~> (`Sec-WebSocket-Accept`(_))
  }

  def `sec-websocket-extensions` = rule {
    oneOrMore(extension).separatedBy(listSep) ~ EOI ~> (`Sec-WebSocket-Extensions`(_))
  }

  def `sec-websocket-key` = rule {
    `base64-value-non-empty` ~ EOI ~> (`Sec-WebSocket-Key`(_))
  }

  def `sec-websocket-protocol` = rule {
    oneOrMore(token).separatedBy(listSep) ~ EOI ~> (`Sec-WebSocket-Protocol`(_))
  }

  def `sec-websocket-version` = rule {
    oneOrMore(version).separatedBy(listSep) ~ EOI ~> (`Sec-WebSocket-Version`(_))
  }

  private def `base64-value-non-empty` = rule {
    capture(oneOrMore(`base64-data`) ~ optional(`base64-padding`) | `base64-padding`)
  }
  private def `base64-data` = rule { 4.times(`base64-character`) }
  private def `base64-padding` = rule {
    2.times(`base64-character`) ~ "==" |
      3.times(`base64-character`) ~ "="
  }
  private def `base64-character` = rfc2045Alphabet

  private def extension = rule {
    `extension-token` ~ zeroOrMore(ws(";") ~ `extension-param`) ~>
      ((name, params) ⇒ WebSocketExtension(name, Map(params: _*)))
  }
  private def `extension-token`: Rule1[String] = token
  private def `extension-param`: Rule1[(String, String)] =
    rule {
      token ~ optional(ws("=") ~ word) ~> ((name: String, value: Option[String]) ⇒ (name, value.getOrElse("")))
    }

  private def version = rule {
    capture(
      NZDIGIT ~ optional(DIGIT ~ optional(DIGIT)) |
        DIGIT) ~> (_.toInt)
  }
  private def NZDIGIT = DIGIT19
}
