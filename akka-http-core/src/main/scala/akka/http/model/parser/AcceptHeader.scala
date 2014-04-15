/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import org.parboiled2.Parser
import headers._

private[parser] trait AcceptHeader { this: Parser with CommonRules with CommonActions ⇒
  import CharacterClasses._

  // http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.2
  def accept = rule {
    zeroOrMore(`media-range-decl`).separatedBy(listSep) ~ EOI ~> (Accept(_: _*))
  }

  def `media-range-decl` = rule {
    `media-range-def` ~ OWS ~ zeroOrMore(ws(';') ~ parameter) ~> { (main, sub, params) ⇒
      if (sub == "*") {
        val mainLower = main.toLowerCase
        MediaRanges.getForKey(mainLower) match {
          case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParameters(params.toMap)
          case None             ⇒ MediaRange.custom(mainLower, params.toMap)
        }
      } else {
        val (p, q) = MediaRange.splitOffQValue(params.toMap)
        MediaRange(getMediaType(main, sub, p), q)
      }
    }
  }

  def `media-range-def` = rule {
    "*/*" ~ push("*") ~ push("*") | `type` ~ '/' ~ ('*' ~ !tchar ~ push("*") | subtype) | '*' ~ push("*") ~ push("*")
  }
}

