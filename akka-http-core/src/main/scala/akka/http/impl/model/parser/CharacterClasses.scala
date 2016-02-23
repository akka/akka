/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.parboiled2.CharPredicate

// efficient encoding of *7-bit* ASCII characters
private[http] object CharacterClasses {

  // http://tools.ietf.org/html/rfc7230#section-1.2 referencing
  // http://tools.ietf.org/html/rfc5234#appendix-B.1
  def ALPHA = CharPredicate.Alpha
  def LOWER_ALPHA = CharPredicate.LowerAlpha
  def UPPER_ALPHA = CharPredicate.UpperAlpha
  def CR = '\r'
  val CTL = CharPredicate('\u0000' to '\u001F', '\u007F')
  def DIGIT = CharPredicate.Digit
  def ALPHANUM = CharPredicate.AlphaNum
  def DQUOTE = '"'
  def HEXDIG = CharPredicate.HexDigit
  def HTAB = '\t'
  def LF = '\n'
  def SP = ' '
  def VCHAR = CharPredicate.Visible
  val WSP = CharPredicate(SP, HTAB)
  val WSPCRLF = WSP ++ CR ++ LF

  // http://tools.ietf.org/html/rfc7230#section-3.2.6
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
  val `strict-query-char` = `query-fragment-char` -- "&=;"
  val `strict-query-char-np` = `strict-query-char` -- '+'

  val `relaxed-fragment-char` = VCHAR -- '%'
  val `relaxed-path-segment-char` = VCHAR -- "%/?#"
  val `relaxed-query-char` = VCHAR -- "%&=#"
  val `raw-query-char` = VCHAR -- '#'
  val `scheme-char` = ALPHA ++ DIGIT ++ '+' ++ '-' ++ '.'

  val `userinfo-char` = unreserved ++ `sub-delims` ++ ':'
  val `reg-name-char` = unreserved ++ `sub-delims`
  val `lower-reg-name-char` = `reg-name-char` -- UPPER_ALPHA

  // http://tools.ietf.org/html/rfc7235#section-2.1
  val `token68-start` = ALPHA ++ DIGIT ++ "-._~+/"

  // https://tools.ietf.org/html/rfc6265#section-4.1.1
  val `cookie-octet-rfc-6265` = CharPredicate('\u0021', '\u0023' to '\u002b', '\u002d' to '\u003a', '\u003c' to '\u005b', '\u005d' to '\u007e')
  val `cookie-separator` = CharPredicate(akka.parboiled2.EOI, ';')
  val `cookie-octet-raw` =
    CharPredicate('\u0020' to '\u007e') ++
      CharPredicate((x: Char) ⇒ x > 0x7f && java.lang.Character.isDefined(x)) -- `cookie-separator`
  val `av-octet` = CharPredicate('\u0020' to '\u003a', '\u003c' to '\u007e') // http://www.rfc-editor.org/errata_search.php?rfc=6265

  // http://tools.ietf.org/html/rfc5988#section-5
  val `reg-rel-type-octet` = LOWER_ALPHA ++ DIGIT ++ '.' ++ '-'

  // helpers
  val `qdtext-base` = CharPredicate(HTAB, SP, '\u0021', '\u0023' to '\u005B', '\u005D' to '\u007E')
  val `ctext-base` = CharPredicate(HTAB, SP, '\u0021' to '\u0027', '\u002A' to '\u005B', '\u005D' to '\u007E')
  val `quotable-base` = CharPredicate(HTAB, SP, VCHAR)
  val `etagc-base` = VCHAR -- '"'
  val DIGIT04 = CharPredicate('0' to '4')
  val DIGIT05 = CharPredicate('0' to '5')
  def DIGIT19 = CharPredicate.Digit19
  val colonSlashEOI = CharPredicate(':', '/', akka.parboiled2.EOI)
  val `date-sep` = CharPredicate("""- """)

  require(`qdtext-base`.isMaskBased) // make sure we didn't introduce any non-7bit-chars by accident which
  require(`ctext-base`.isMaskBased) // would make the CharPredicate fall back to the much slower
  require(`quotable-base`.isMaskBased) // ArrayBasedPredicate or GeneralCharPredicate implementations
  require(CTL.isMaskBased)
}
