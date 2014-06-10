/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import akka.parboiled2.Parser

private[parser] trait AcceptCharsetHeader { this: Parser with CommonRules with CommonActions ⇒

  // http://tools.ietf.org/html/rfc7231#section-5.3.3
  def `accept-charset` = rule {
    oneOrMore(`charset-range-decl`).separatedBy(listSep) ~ EOI ~> (headers.`Accept-Charset`(_))
  }

  def `charset-range-decl` = rule {
    `charset-range-def` ~ optional(weight) ~> { (range, optQ) ⇒
      optQ match {
        case None    ⇒ range
        case Some(q) ⇒ range withQValue q
      }
    }
  }

  def `charset-range-def` = rule {
    ws('*') ~ push(HttpCharsetRange.`*`) | token ~> (s ⇒ HttpCharsetRange(getCharset(s)))
  }
}