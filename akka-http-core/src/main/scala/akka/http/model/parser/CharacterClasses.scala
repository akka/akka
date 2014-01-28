/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.parser

import org.parboiled2.CharPredicate
import org.parboiled2.CharPredicate.CharMask

// efficient encoding of *7-bit* ASCII characters
private[http] object CharacterClasses {

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-1.2 referencing
  // http://tools.ietf.org/html/rfc5234#appendix-B.1
  def ALPHA = CharPredicate.Alpha
  def UPPER_ALPHA = CharPredicate.UpperAlpha
  def CR = '\n'
  val CTL = CharPredicate('\u0000' to '\u001F', '\u007F')
  def DIGIT = CharPredicate.Digit
  def ALPHANUM = CharPredicate.AlphaNum
  def DQUOTE = '"'
  def HEXDIG = CharPredicate.HexDigit
  def HTAB = '\t'
  def LF = '\r'
  def SP = ' '
  def VCHAR = CharPredicate.Visible
  val WSP = CharPredicate(SP, HTAB)

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-25#section-3.2.6
  val special = CharPredicate("""()<>@,;:\"/[]?={}""")
  val tchar = VCHAR -- special // token-char

  // http://tools.ietf.org/html/rfc3986#appendix-A
  val unreserved = ALPHA ++ DIGIT ++ "-._~"
  val `gen-delims` = CharPredicate(":/?#[]@")
  val `sub-delims` = CharPredicate("!$&'()*+,;=")
  val reserved = `gen-delims` ++ `sub-delims`

  // URI FRAGMENT/QUERY and PATH characters have two classes of acceptable characters: one that strictly
  // follows rfc3986, which should be used for rendering urls, and one relaxed, which accepts all visible
  // 7-bit ASCII characters, even if they're not percent-encoded.
  val `pchar-base-nc` = unreserved ++ `sub-delims` ++ '@'
  val `pchar-base` = `pchar-base-nc` ++ ':' // pchar without percent
  val `query-fragment-char` = `pchar-base` ++ "/?"
  val `strict-query-char` = `query-fragment-char` -- "&="
  val `strict-query-char-np` = `strict-query-char` -- '+'

  val `relaxed-fragment-char` = VCHAR -- '%'
  val `relaxed-path-segment-char` = VCHAR -- "%/?#"
  val `relaxed-query-char` = VCHAR -- "%&=#"
  val `raw-query-char` = VCHAR -- '#'
  val `scheme-char` = ALPHA ++ DIGIT ++ '+' ++ '-' ++ '.'

  val `userinfo-char` = unreserved ++ `sub-delims` ++ ':'
  val `reg-name-char` = unreserved ++ `sub-delims`
  val `lower-reg-name-char` = `reg-name-char` -- UPPER_ALPHA

  // helpers
  val `qdtext-base` = CharPredicate(HTAB, SP, '\u0021', '\u0023' to '\u005B', '\u005D' to '\u007E')
  val `ctext-base` = CharPredicate(HTAB, SP, '\u0021' to '\u0027', '\u002A' to '\u005B', '\u005D' to '\u007E')
  val `quotable-base` = CharPredicate(HTAB, SP, VCHAR)
  val DIGIT04 = CharPredicate('0' to '4')
  val DIGIT05 = CharPredicate('0' to '5')
  def DIGIT19 = CharPredicate.Digit19
  val colonSlashEOI = CharPredicate(':', '/', org.parboiled2.EOI)

  require(`qdtext-base`.isCharMask) // make sure we didn't introduce any non-7bit-chars by accident which
  require(`ctext-base`.isCharMask) // would make the CharPredicate fall back to the much slower
  require(`quotable-base`.isCharMask) // ArrayBasedPredicate or GeneralCharPredicate implementations
  require(CTL.isCharMask)
}
