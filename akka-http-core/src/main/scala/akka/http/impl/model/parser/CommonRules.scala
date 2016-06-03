/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import scala.collection.immutable
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.parboiled2._
import akka.shapeless._

private[parser] trait CommonRules { this: Parser with StringBuilding ⇒
  import CharacterClasses._

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7230#section-1.2 referencing
  // http://tools.ietf.org/html/rfc5234#appendix-B.1
  // ******************************************************************************************
  def CRLF = rule { CR ~ LF }

  def OCTET = rule { ANY }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7230#section-3.2.3
  // ******************************************************************************************

  def OWS = rule { zeroOrMore(optional(CRLF) ~ oneOrMore(WSP)) } // extended with `obs-fold`

  def RWS = rule { oneOrMore(optional(CRLF) ~ oneOrMore(WSP)) } // extended with `obs-fold`

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7230#section-3.2.6
  // ******************************************************************************************
  def word = rule { token | `quoted-string` }

  def token: Rule1[String] = rule { capture(token0) ~ OWS }

  def `quoted-string`: Rule1[String] = rule {
    DQUOTE ~ clearSB() ~ zeroOrMore(qdtext ~ appendSB() | `quoted-pair`) ~ push(sb.toString) ~ DQUOTE ~ OWS
  }

  def qdtext = rule { `qdtext-base` | `obs-text` }

  def `obs-text` = rule { "\u0080" - "\uFFFE" }

  def `quoted-pair` = rule { '\\' ~ (`quotable-base` | `obs-text`) ~ appendSB() }

  // builds a string via the StringBuilding StringBuilder
  def comment: Rule0 = rule {
    ws('(') ~ clearSB() ~ zeroOrMore(ctext | `quoted-cpair` | `nested-comment`) ~ ws(')')
  }

  def `nested-comment` = {
    var saved: String = null
    rule { &('(') ~ run(saved = sb.toString) ~ (comment ~ prependSB(saved + " (") ~ appendSB(')') | setSB(saved) ~ test(false)) }
  }

  def ctext = rule { (`ctext-base` | `obs-text`) ~ appendSB() }

  def `quoted-cpair` = `quoted-pair`

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7234#section-5.3
  // ******************************************************************************************

  def `expires-date`: Rule1[DateTime] = rule {
    (`HTTP-date` | zeroOrMore(ANY) ~ push(DateTime.MinValue)) ~ OWS
  }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7231#section-7.1.1.1
  // but more lenient where we have already seen differing implementations in the field
  // ******************************************************************************************

  def `HTTP-date`: Rule1[DateTime] = rule {
    (`IMF-fixdate` | `asctime-date` | '0' ~ push(DateTime.MinValue)) ~ OWS
  }

  def `IMF-fixdate` = rule { // mixture of the spec-ed `IMF-fixdate` and `rfc850-date`
    (`day-name-l` | `day-name`) ~ ", " ~ (date1 | date2) ~ ' ' ~ `time-of-day` ~ ' ' ~ ("GMT" | "UTC") ~> {
      (wkday, day, month, year, hour, min, sec) ⇒ createDateTime(year, month, day, hour, min, sec, wkday)
    }
  }

  def `day-name` = rule(
    "Sun" ~ push(0) | "Mon" ~ push(1) | "Tue" ~ push(2) | "Wed" ~ push(3) | "Thu" ~ push(4) | "Fri" ~ push(5) | "Sat" ~ push(6))

  def date1 = rule { day ~ `date-sep` ~ month ~ `date-sep` ~ year }

  def day = rule { digit2 }

  def month = rule(
    "Jan" ~ push(1) | "Feb" ~ push(2) | "Mar" ~ push(3) | "Apr" ~ push(4) | "May" ~ push(5) | "Jun" ~ push(6) | "Jul" ~ push(7) |
      "Aug" ~ push(8) | "Sep" ~ push(9) | "Oct" ~ push(10) | "Nov" ~ push(11) | "Dec" ~ push(12))

  def year = rule { digit4 }

  def `time-of-day` = rule { hour ~ ':' ~ minute ~ ':' ~ second }
  def hour = rule { digit2 }
  def minute = rule { digit2 }
  def second = rule { digit2 }

  // def `obs-date` = rule { `rfc850-date` | `asctime-date` }

  // def `rfc850-date` = rule { `day-name-l` ~ ", " ~ date2 ~ ' ' ~ `time-of-day` ~ " GMT" }

  // per #17714, parse two digit year to https://tools.ietf.org/html/rfc6265#section-5.1.1
  def date2 = rule { day ~ '-' ~ month ~ '-' ~ digit2 ~> (y ⇒ if (y <= 69) y + 2000 else y + 1900) }

  def `day-name-l` = rule(
    "Sunday" ~ push(0) | "Monday" ~ push(1) | "Tuesday" ~ push(2) | "Wednesday" ~ push(3) | "Thursday" ~ push(4) |
      "Friday" ~ push(5) | "Saturday" ~ push(6))

  def `asctime-date` = rule {
    `day-name` ~ ' ' ~ date3 ~ ' ' ~ `time-of-day` ~ ' ' ~ year ~> {
      (wkday, month, day, hour, min, sec, year) ⇒ createDateTime(year, month, day, hour, min, sec, wkday)
    }
  }

  def date3 = rule { month ~ ' ' ~ (digit2 | ' ' ~ digit) }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7231#section-5.3.1
  // ******************************************************************************************

  def weight = rule { ws(';') ~ ws('q') ~ ws('=') ~ qvalue } // a bit more lenient than the spec

  def qvalue = rule { // a bit more lenient than the spec
    capture('0' ~ optional('.' ~ zeroOrMore(DIGIT))
      | '.' ~ oneOrMore(DIGIT)
      | '1' ~ optional('.' ~ zeroOrMore('0'))) ~> (_.toFloat) ~ OWS
  }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7231#section-3.1.1.1
  // ******************************************************************************************

  def `media-type`: RuleN[String :: String :: Seq[(String, String)] :: HNil] = rule {
    `type` ~ '/' ~ subtype ~ zeroOrMore(ws(';') ~ parameter)
  }

  def `type` = rule { token }

  def subtype = rule { token }

  def parameter = rule { attribute ~ ws('=') ~ value ~> ((_, _)) }

  def attribute = rule { token }

  def value = rule { word }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc4647#section-2.1
  // ******************************************************************************************
  def language = rule {
    `primary-tag` ~ zeroOrMore('-' ~ `sub-tag`) ~> (Language(_, _: _*))
  }

  def `primary-tag` = rule { capture(oneOrMore(ALPHA)) ~ OWS }

  def `sub-tag` = rule { capture(oneOrMore(ALPHANUM)) ~ OWS }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc4647#section-2.1
  // ******************************************************************************************

  def `auth-scheme` = rule { token }

  def `auth-param` = rule { token ~ ws('=') ~ word }

  def `token68` = rule { capture(oneOrMore(`token68-start`) ~ zeroOrMore('=')) ~ OWS }

  def challenge = rule {
    `challenge-or-credentials` ~> { (scheme, params) ⇒
      val (realms, otherParams) = params.partition(_._1 equalsIgnoreCase "realm")
      HttpChallenge(scheme, realms.headOption.map(_._2).getOrElse(""), otherParams.toMap)
    }
  }

  def `challenge-or-credentials`: Rule2[String, Seq[(String, String)]] = rule {
    `auth-scheme` ~ (
      oneOrMore(`auth-param` ~> (_ → _)).separatedBy(listSep)
      | `token68` ~> (x ⇒ ("" → x) :: Nil)
      | push(Nil))
  }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7234#section-1.2.1
  // ******************************************************************************************

  def `delta-seconds` = rule { longNumberCappedAtIntMaxValue }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7232#section-2.3
  // ******************************************************************************************

  def `entity-tag` = rule {
    ("W/" ~ push(true) | push(false)) ~ `opaque-tag` ~> ((weak, tag) ⇒ EntityTag(tag, weak))
  }

  def `opaque-tag` = rule { '"' ~ capture(zeroOrMore(`etagc-base` | `obs-text`)) ~ '"' }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7235#section-2.1
  // ******************************************************************************************
  def credentials = rule {
    `basic-credential-def` | `oauth2-bearer-token` | `generic-credentials`
  }

  def `basic-credential-def` = rule {
    ignoreCase("basic") ~ OWS ~ `basic-cookie` ~> (BasicHttpCredentials(_))
  }

  def `basic-cookie` = rule { `token68` }

  // http://tools.ietf.org/html/rfc6750#section-2.1
  def `oauth2-bearer-token` = rule {
    ignoreCase("bearer") ~ OWS ~ `token68` ~> OAuth2BearerToken
  }

  def `generic-credentials` = rule {
    `challenge-or-credentials` ~> ((scheme, params) ⇒ GenericHttpCredentials(scheme, params.toMap))
  }

  /**
   * Either `Some(cookiePair)` if the cookie pair is parsable using the giving cookie parsing mode
   * or None, otherwise.
   */
  def `optional-cookie-pair`: Rule1[Option[HttpCookiePair]] = rule {
    (`cookie-pair` ~ &(`cookie-separator`) ~> (Some(_: HttpCookiePair))) |
      // fallback that parses and discards everything until the next semicolon
      (zeroOrMore(!`cookie-separator` ~ ANY) ~ &(`cookie-separator`) ~ push(None))
  }

  def `cookie-pair`: Rule1[HttpCookiePair] = rule {
    `cookie-name` ~ ws('=') ~ `cookie-value` ~> (createCookiePair _)
  }

  def `cookie-name` = rule { token }

  // abstract methods need to be implemented depending on actual cookie parsing mode
  def `cookie-value`: Rule1[String]
  def createCookiePair(name: String, value: String): HttpCookiePair

  // ******************************************************************************************
  // https://tools.ietf.org/html/rfc6265#section-4.1.1
  // ******************************************************************************************
  def `cookie-value-rfc-6265` = rule {
    ('"' ~ capture(zeroOrMore(`cookie-octet-rfc-6265`)) ~ '"' | capture(zeroOrMore(`cookie-octet-rfc-6265`))) ~ OWS
  }

  def `cookie-value-raw` = rule {
    capture(zeroOrMore(`cookie-octet-raw`)) ~ OWS
  }

  def `cookie-av` = rule {
    `expires-av` | `max-age-av` | `domain-av` | `path-av` | `secure-av` | `httponly-av` | `extension-av`
  }

  def `expires-av` = rule {
    ignoreCase("expires=") ~ OWS ~ `expires-date` ~> { (c: HttpCookie, dt: DateTime) ⇒ c.copy(expires = Some(dt)) }
  }

  def `max-age-av` = rule {
    ignoreCase("max-age=") ~ OWS ~ longNumberCappedAtIntMaxValue ~> { (c: HttpCookie, seconds: Long) ⇒ c.copy(maxAge = Some(seconds)) }
  }

  def `domain-av` = rule {
    ignoreCase("domain=") ~ OWS ~ `domain-value` ~> { (c: HttpCookie, domainName: String) ⇒ c.copy(domain = Some(domainName)) }
  }

  // https://tools.ietf.org/html/rfc1034#section-3.5 relaxed by https://tools.ietf.org/html/rfc1123#section-2
  // to also allow digits at the start of a label
  def `domain-value` = rule {
    optional('.') ~ capture(oneOrMore(oneOrMore(oneOrMore(ALPHANUM)).separatedBy('-')).separatedBy('.')) ~ OWS
  }

  def `path-av` = rule {
    ignoreCase("path=") ~ OWS ~ `path-value` ~> { (c: HttpCookie, pathValue: String) ⇒ c.copy(path = Some(pathValue)) }
  }

  // http://www.rfc-editor.org/errata_search.php?rfc=6265
  def `path-value` = rule {
    capture(zeroOrMore(`av-octet`)) ~ OWS
  }

  def `secure-av` = rule {
    ignoreCase("secure") ~ OWS ~> { (cookie: HttpCookie) ⇒ cookie.copy(secure = true) }
  }

  def `httponly-av` = rule {
    ignoreCase("httponly") ~ OWS ~> { (cookie: HttpCookie) ⇒ cookie.copy(httpOnly = true) }
  }

  // http://www.rfc-editor.org/errata_search.php?rfc=6265
  def `extension-av` = rule {
    !(ignoreCase("expires=")
      | ignoreCase("max-age=")
      | ignoreCase("domain=")
      | ignoreCase("path=")
      | ignoreCase("secure")
      | ignoreCase("httponly")) ~
      capture(zeroOrMore(`av-octet`)) ~ OWS ~> { (c: HttpCookie, s: String) ⇒ c.copy(extension = Some(s)) }
  }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc6454#section-7.1
  // ******************************************************************************************
  def `origin-list-or-null` = rule {
    "null" ~ OWS ~ push(immutable.Seq.empty[HttpOrigin]) | `origin-list`
  }

  def `origin-list` = rule {
    oneOrMore(capture(oneOrMore(VCHAR)) ~> (HttpOrigin(_))).separatedBy(SP) ~ OWS // offload to URL parser
  }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7233#appendix-D
  // ******************************************************************************************

  def `byte-content-range` = rule { `bytes-unit` ~ (`byte-range-resp` | `unsatisfied-range`) }

  def `byte-range` = rule {
    `first-byte-pos` ~ ws('-') ~ `last-byte-pos`
  }

  def `byte-range-resp` = rule {
    `byte-range` ~ ws('/') ~ (`complete-length` ~> (Some(_)) | ws('*') ~ push(None)) ~> (ContentRange(_, _, _))
  }

  def `byte-range-set` = rule {
    zeroOrMore(ws(',')) ~ oneOrMore(`byte-range-spec` | `suffix-byte-range-spec`).separatedBy(listSep)
  }

  def `byte-range-spec` = rule {
    `first-byte-pos` ~ ws('-') ~ (`last-byte-pos` ~> (ByteRange(_: Long, _)) | run(ByteRange.fromOffset(_)))
  }

  def `byte-ranges-specifier` = rule { `bytes-unit` ~ ws('=') ~ `byte-range-set` }

  def `bytes-unit` = rule { "bytes" ~ OWS ~ push(RangeUnits.Bytes) }

  def `complete-length` = rule { longNumberCapped }

  def `first-byte-pos` = rule { longNumberCapped }

  def `last-byte-pos` = rule { longNumberCapped }

  def `other-content-range` = rule { `other-range-unit` ~ `other-range-resp` }

  def `other-range-resp` = rule { capture(zeroOrMore(ANY)) ~> ContentRange.Other }

  def `other-range-set` = rule { oneOrMore(VCHAR) ~ OWS }

  def `other-range-unit` = rule { token ~> RangeUnits.Other }

  def `other-ranges-specifier` = rule { `other-range-unit` ~ ws('=') ~ `other-range-set` }

  def `range-unit` = rule { `bytes-unit` | `other-range-unit` }

  def `suffix-byte-range-spec` = rule { '-' ~ `suffix-length` ~> (ByteRange.suffix(_)) }

  def `suffix-length` = rule { longNumberCapped }

  def `unsatisfied-range` = rule { '*' ~ '/' ~ `complete-length` ~> (ContentRange.Unsatisfiable(_)) }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7231#section-5.5.3
  // ******************************************************************************************

  def product = rule { token ~ (ws('/') ~ `product-version` | push("")) }

  def `product-version` = rule { token }

  def `product-or-comment` = rule(
    product ~ comment ~> (ProductVersion(_, _, sb.toString))
      | product ~> (ProductVersion(_, _))
      | comment ~ push(ProductVersion("", "", sb.toString)))

  def products = rule {
    `product-or-comment` ~ zeroOrMore(`product-or-comment`) ~> (_ +: _)
  }

  // ******************************************************************************************
  // http://tools.ietf.org/html/rfc7230#section-4
  // ******************************************************************************************

  def `transfer-coding` = rule(
    ignoreCase("chunked") ~ OWS ~ push(TransferEncodings.chunked)
      | ignoreCase("gzip") ~ OWS ~ push(TransferEncodings.gzip)
      | ignoreCase("deflate") ~ OWS ~ push(TransferEncodings.deflate)
      | ignoreCase("compress") ~ OWS ~ push(TransferEncodings.compress)
      | `transfer-extension`)

  def `transfer-extension` = rule {
    token ~ zeroOrMore(ws(';') ~ `transfer-parameter`) ~> (_.toMap) ~> (TransferEncodings.Extension(_, _))
  }

  def `transfer-parameter` = rule { token ~ ws('=') ~ word ~> (_ → _) }

  // ******************************************************************************************
  //                                    helpers
  // ******************************************************************************************
  def token0 = rule { oneOrMore(tchar) }

  def listSep = rule { ',' ~ OWS }

  def digit = rule { DIGIT ~ push(digitInt(lastChar)) }

  def digit2 = rule { DIGIT ~ DIGIT ~ push(digitInt(charAt(-2)) * 10 + digitInt(lastChar)) }

  def digit4 = rule {
    DIGIT ~ DIGIT ~ DIGIT ~ DIGIT ~ push(digitInt(charAt(-4)) * 1000 + digitInt(charAt(-3)) * 100 + digitInt(charAt(-2)) * 10 + digitInt(lastChar))
  }

  def ws(c: Char) = rule { c ~ OWS }
  def ws(s: String) = rule { s ~ OWS }

  // parses a potentially long series of digits and extracts its Long value capping at Int.MaxValue in case of overflows
  def longNumberCappedAtIntMaxValue = rule {
    capture((1 to 11).times(DIGIT)) ~> (s ⇒ math.min(s.toLong, Int.MaxValue)) ~ zeroOrMore(DIGIT) ~ OWS
  }

  // parses a potentially long series of digits and extracts its Long value capping at 999,999,999,999,999,999 in case of overflows
  def longNumberCapped = rule(
    (capture((1 to 18).times(DIGIT)) ~ !DIGIT ~> (_.toLong)
      | oneOrMore(DIGIT) ~ push(999999999999999999L)) ~ OWS)

  private def digitInt(c: Char): Int = c - '0'

  private def createDateTime(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int, wkday: Int) = {
    val dt = DateTime(year, month, day, hour, min, sec)
    if (dt.weekday != wkday)
      throw ParsingException(s"Illegal weekday in date $dt: is '${DateTime.weekday(wkday)}' but " +
        s"should be '${DateTime.weekday(dt.weekday)}'")
    dt
  }

  def httpMethodDef = rule {
    token ~> { s ⇒
      HttpMethods.getForKey(s) match {
        case Some(m) ⇒ m
        case None    ⇒ HttpMethod.custom(s)
      }
    }
  }

  def newUriParser(input: ParserInput): UriParser
  def uriReference: Rule1[Uri] = rule { runSubParser(newUriParser(_).`URI-reference-pushed`) }
}

