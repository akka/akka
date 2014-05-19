/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import org.parboiled2.Parser
import headers._

private[parser] trait AcceptLanguageHeader { this: Parser with CommonRules with CommonActions ⇒

  // http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.5
  def `accept-language` = rule {
    oneOrMore(`language-range-decl`).separatedBy(listSep) ~ EOI ~> (`Accept-Language`(_))
  }

  def `language-range-decl` = rule {
    `language-range` ~ optional(weight) ~> { (range, optQ) ⇒
      optQ match {
        case None    ⇒ range
        case Some(q) ⇒ range withQValue q
      }
    }
  }

  def `language-range` = rule { ws('*') ~ push(LanguageRange.`*`) | language }
}