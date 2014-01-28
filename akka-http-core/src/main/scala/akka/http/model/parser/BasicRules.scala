/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.parser

import org.parboiled2._
import shapeless._

// direct implementation of http://www.w3.org/Protocols/rfc2616/rfc2616-sec2.html#sec2
private[parser] trait BasicRules { this: Parser ⇒
  import CharacterClasses._

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-1.2 referencing
  // http://tools.ietf.org/html/rfc5234#appendix-B.1
  def CRLF = rule { CR ~ LF }

  def OCTET = rule { ANY }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-3.2.6
  def word = rule { token0 | `quoted-string` }

  def token: Rule1[String] = rule { capture(token0) }

  def `quoted-string` = rule { DQUOTE ~ zeroOrMore(qdtext | `quoted-pair`) ~ DQUOTE }

  def qdtext = rule { `qdtext-base` | `obs-text` }

  def `obs-text` = rule { "\u0080" - "\u00FF" }

  def `quoted-pair` = rule { '\\' ~ (`quotable-base` | `obs-text`) }

  def comment: Rule0 = rule { '(' ~ zeroOrMore(ctext | `quoted-cpair` | comment) ~ ')' }

  def ctext = rule { '\\' ~ (`ctext-base` | `obs-text`) }

  def `quoted-cpair` = `quoted-pair`

  // helpers
  def token0 = rule { oneOrMore(tchar) }

  def LWS = rule { optional(CRLF) ~ oneOrMore(WSP) }

  def text = rule { !CTL ~ ANY | LWS }

  def optWS = rule { zeroOrMore(LWS) }

  def listSep = rule { oneOrMore(',' ~ optWS) }
}

