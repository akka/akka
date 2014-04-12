/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.parser

import org.parboiled2._
import akka.http.util.DateTime
import akka.http.model.ParsingException

private[parser] trait CommonRules { this: Parser ⇒
  import CharacterClasses._

  // ******************************************************************************************
  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-1.2 referencing
  // http://tools.ietf.org/html/rfc5234#appendix-B.1
  // ******************************************************************************************
  def CRLF = rule { CR ~ LF }

  def OCTET = rule { ANY }

  // ******************************************************************************************
  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-3.2.3
  // ******************************************************************************************

  def OWS = rule { zeroOrMore(optional(CRLF) ~ oneOrMore(WSP)) } // extended with `obs-fold`

  def RWS = rule { oneOrMore(optional(CRLF) ~ oneOrMore(WSP)) } // extended with `obs-fold`

  // ******************************************************************************************
  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-3.2.6
  // ******************************************************************************************
  def word = rule { token0 | `quoted-string` }

  def token: Rule1[String] = rule { capture(token0) ~ OWS }

  def `quoted-string` = rule { DQUOTE ~ zeroOrMore(qdtext | `quoted-pair`) ~ DQUOTE }

  def qdtext = rule { `qdtext-base` | `obs-text` }

  def `obs-text` = rule { "\u0080" - "\u00FF" }

  def `quoted-pair` = rule { '\\' ~ (`quotable-base` | `obs-text`) }

  def comment: Rule0 = rule { '(' ~ zeroOrMore(ctext | `quoted-cpair` | comment) ~ ')' }

  def ctext = rule { '\\' ~ (`ctext-base` | `obs-text`) }

  def `quoted-cpair` = `quoted-pair`

  // ******************************************************************************************
  // http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-25#section-7.1.1.1
  // but more lenient where we have already seen differing implementations in the field
  // ******************************************************************************************

  def `HTTP-date`: Rule1[DateTime] = rule { (`IMF-fixdate` | `asctime-date`) ~ OWS }

  def `IMF-fixdate` = rule { // mixture of the spec-ed `IMF-fixdate` and `rfc850-date`
    (`day-name` | `day-name-l`) ~ ", " ~ (date1 | date2) ~ ' ' ~ `time-of-day` ~ ' ' ~ ("GMT" | "UTC") ~> {
      (wkday, day, month, year, hour, min, sec) ⇒ createDateTime(year, month, day, hour, min, sec, wkday)
    }
  }

  def `day-name` = rule {
    ("Mon" -> 0) | ("Tue" -> 1) | ("Wed" -> 2) | ("Thu" -> 3) | ("Fri" -> 4) | ("Sat" -> 5) | ("Sun" -> 6)
  }

  def date1 = rule { day ~ ' ' ~ month ~ ' ' ~ year }

  def day = rule { digit2 }

  def month = rule {
    ("Jan" -> 1) | ("Feb" -> 2) | ("Mar" -> 3) | ("Apr" -> 4) | ("May" -> 5) | ("Jun" -> 6) | ("Jul" -> 7) |
      ("Aug" -> 8) | ("Sep" -> 9) | ("Oct" -> 10) | ("Nov" -> 11) | ("Dec" -> 12)
  }

  def year = rule { digit4 }

  def `time-of-day` = rule { hour ~ ':' ~ minute ~ ':' ~ second }
  def hour = rule { digit2 }
  def minute = rule { digit2 }
  def second = rule { digit2 }

  // def `obs-date` = rule { `rfc850-date` | `asctime-date` }

  // def `rfc850-date` = rule { `day-name-l` ~ ", " ~ date2 ~ ' ' ~ `time-of-day` ~ " GMT" }

  def date2 = rule { day ~ '-' ~ month ~ '-' ~ digit2 }

  def `day-name-l` = rule {
    ("Monday" -> 0) | ("Tuesday" -> 1) | ("Wednesday" -> 2) | ("Thursday" -> 3) | ("Friday" -> 4) | ("Saturday" -> 5) |
      ("Sunday" -> 6)
  }

  def `asctime-date` = rule {
    `day-name` ~ ' ' ~ date3 ~ ' ' ~ `time-of-day` ~ ' ' ~ year ~> {
      (wkday, month, day, hour, min, sec, year) ⇒ createDateTime(year, month, day, hour, min, sec, wkday)
    }
  }

  def date3 = rule { month ~ ' ' ~ (digit2 | ' ' ~ firstDigit) }

  // ******************************************************************************************
  // http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-25#section-5.3.1
  // ******************************************************************************************

  def weight = rule { ws(';') ~ ws('q') ~ ws('=') ~ qvalue } // a bit more lenient than the spec

  def qvalue = rule { // a bit more lenient than the spec
    capture('0' ~ optional('.' ~ zeroOrMore(DIGIT))
      | '.' ~ oneOrMore(DIGIT)
      | '1' ~ optional('.' ~ zeroOrMore('0'))) ~> (_.toFloat) ~ OWS
  }

  // ******************************************************************************************
  //                                    helpers
  // ******************************************************************************************
  def token0 = rule { oneOrMore(tchar) }

  def listSep = rule { ',' ~ OWS }

  def firstDigit = rule { DIGIT ~ push(lastChar - '0') }

  def nextDigit = rule { DIGIT ~ run((i: Int) ⇒ push(i * 10 + lastChar - '0')) }

  def digit2 = rule { firstDigit ~ nextDigit }

  def digit4 = rule { firstDigit ~ nextDigit ~ nextDigit ~ nextDigit }

  def ws(c: Char) = rule { c ~ OWS }
  def ws(s: String) = rule { s ~ OWS }

  private def createDateTime(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int, wkday: Int) = {
    val dt = DateTime(year, month, day, hour, min, sec)
    if (dt.weekday != wkday)
      throw new ParsingException("Illegal weekday in date: is '" + DateTime.WEEKDAYS(wkday) +
        "' but should be '" + DateTime.WEEKDAYS(dt.weekday) + "')" + dt)
    dt
  }
}

