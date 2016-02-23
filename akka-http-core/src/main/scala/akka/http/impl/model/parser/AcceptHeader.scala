/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.parboiled2.Parser
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ MediaRange, MediaRanges }
import akka.http.impl.util._

private[parser] trait AcceptHeader { this: Parser with CommonRules with CommonActions ⇒
  import CharacterClasses._

  // http://tools.ietf.org/html/rfc7231#section-5.3.2
  def accept = rule {
    zeroOrMore(`media-range-decl`).separatedBy(listSep) ~ EOI ~> (Accept(_))
  }

  def `media-range-decl` = rule {
    `media-range-def` ~ OWS ~ zeroOrMore(ws(';') ~ parameter) ~> { (main, sub, params) ⇒
      if (sub == "*") {
        val mainLower = main.toRootLowerCase
        MediaRanges.getForKey(mainLower) match {
          case Some(registered) ⇒ if (params.isEmpty) registered else registered.withParams(params.toMap)
          case None             ⇒ MediaRange.custom(mainLower, params.toMap)
        }
      } else {
        val (p, q) = MediaRange.splitOffQValue(params.toMap)
        MediaRange(getMediaType(main, sub, p contains "charset", p), q)
      }
    }
  }

  def `media-range-def` = rule {
    "*/*" ~ push("*") ~ push("*") | `type` ~ '/' ~ ('*' ~ !tchar ~ push("*") | subtype) | '*' ~ push("*") ~ push("*")
  }
}

