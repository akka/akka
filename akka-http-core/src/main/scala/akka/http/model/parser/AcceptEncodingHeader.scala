/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import akka.parboiled2.Parser
import headers._

private[parser] trait AcceptEncodingHeader { this: Parser with CommonRules with CommonActions ⇒

  // http://tools.ietf.org/html/rfc7231#section-5.3.4
  def `accept-encoding` = rule {
    zeroOrMore(`encoding-range-decl`).separatedBy(listSep) ~ EOI ~> (`Accept-Encoding`(_: _*))
  }

  def `encoding-range-decl` = rule {
    codings ~ optional(weight) ~> { (range, optQ) ⇒
      optQ match {
        case None    ⇒ range
        case Some(q) ⇒ range withQValue q
      }
    }
  }

  def codings = rule { ws('*') ~ push(HttpEncodingRange.`*`) | token ~> getEncoding }

  private val getEncoding: String ⇒ HttpEncodingRange =
    name ⇒ HttpEncodingRange(HttpEncodings.getForKey(name.toLowerCase) getOrElse HttpEncoding.custom(name))
}