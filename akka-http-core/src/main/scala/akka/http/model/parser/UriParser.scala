package akka.http.model
package parser

import java.nio.charset.Charset
import java.lang.{ StringBuilder ⇒ JStringBuilder }
import scala.annotation.tailrec
import scala.collection.immutable.NumericRange
import akka.http.model.headers
import akka.http.model.headers.HttpOrigin
import Uri._
import UriParser._

private[http] class UriParser(input: ParserInput, charset: Charset, mode: Uri.ParsingMode) {
  private[this] var cursor: Int = 0
  private[this] var maxCursor: Int = 0
  private[this] var firstUpper = -1
  private[this] var firstPercent = -1
  var _scheme = ""
  var _userinfo = ""
  var _host: Host = Host.Empty
  var _port: Int = 0
  var _path: Path = Path.Empty
  var _query: Query = Query.Empty
  var _fragment: Option[String] = None

  private[this] val PARSE_PATH_SEGMENT_CHAR = mode match {
    case Uri.ParsingMode.Strict ⇒ PATH_SEGMENT_CHAR
    case _                      ⇒ RELAXED_PATH_SEGMENT_CHAR
  }
  private[this] val PARSE_QUERY_CHAR = mode match {
    case Uri.ParsingMode.Strict              ⇒ QUERY_FRAGMENT_CHAR & ~(AMP | EQUAL)
    case Uri.ParsingMode.Relaxed             ⇒ RELAXED_QUERY_CHAR
    case Uri.ParsingMode.RelaxedWithRawQuery ⇒ RAW_QUERY_CHAR
  }
  private[this] val PARSE_FRAGMENT_CHAR = mode match {
    case Uri.ParsingMode.Strict ⇒ QUERY_FRAGMENT_CHAR
    case _                      ⇒ RELAXED_FRAGMENT_CHAR
  }

  def parseAbsolute(): Uri = {
    complete("absolute URI", `absolute-URI`)
    create(_scheme, _userinfo, _host, _port, collapseDotSegments(_path), _query, _fragment)
  }

  def parseReference(): Uri = {
    complete("URI reference", `URI-reference`)
    val path = if (_scheme.isEmpty) _path else collapseDotSegments(_path)
    create(_scheme, _userinfo, _host, _port, path, _query, _fragment)
  }

  def parseAndResolveReference(base: Uri): Uri = {
    complete("URI reference", `URI-reference`)
    resolve(_scheme, _userinfo, _host, _port, _path, _query, _fragment, base)
  }

  def parseOrigin(): HttpOrigin = {
    complete("origin", origin)
    HttpOrigin(_scheme, headers.Host(_host.address, _port))
  }

  def URI =
    scheme && ch(':') && `hier-part` && {
      val mark = cursor
      ch('?') && query || reset(mark)
    } && {
      val mark = cursor
      ch('#') && fragment || reset(mark)
    }

  def origin = scheme && ch(':') && ch('/') && ch('/') && hostAndPort

  def `hier-part` = {
    val mark = cursor
    (ch('/') && ch('/') && authority && `path-abempty`
      || reset(mark) && `path-absolute`
      || reset(mark) && `path-rootless`
      || reset(mark) && `path-empty`)
  }

  def `URI-reference` = {
    val mark = cursor
    URI || reset(mark) && `relative-ref`
  }

  def `absolute-URI` =
    scheme && ch(':') && `hier-part` && {
      val mark = cursor
      ch('?') && query || reset(mark)
    }

  def `relative-ref` =
    `relative-part` && {
      val mark = cursor
      ch('?') && query || reset(mark)
    } && {
      val mark = cursor
      ch('#') && fragment || reset(mark)
    }

  def `relative-part` = {
    val mark = cursor
    (ch('/') && ch('/') && authority && `path-abempty`
      || reset(mark) && `path-absolute`
      || reset(mark) && `path-noscheme`
      || reset(mark) && `path-empty`)
  }

  def scheme = {
    def setScheme(s: String) = { _scheme = s; true }
    def endOfScheme = is(current, COLON)
    val start = cursor
    ch('h') && ch('t') && ch('t') && ch('p') && (
      endOfScheme && setScheme("http") || ch('s') && endOfScheme && setScheme("https")) || reset(start) && resetFirstUpper() && Alpha && {
        def matches(c: Char) = is(c, LOWER_ALPHA | DIGIT | PLUS | DASH | DOT) || is(c, UPPER_ALPHA) && markUpper()
        while (matches(current)) advance()
        endOfScheme && setScheme(toLowerIfNeeded(slice(start, cursor), firstUpper - start))
      }
  }

  def authority = {
    val mark = cursor
    (userinfo || reset(mark)) && hostAndPort
  }

  def userinfo = {
    val start = cursor
    var mark = start
    resetFirstPercent()
    while (matches(UNRESERVED | SUB_DELIM | COLON) || `pct-encoded`) mark = cursor
    reset(mark)
    ch('@') && {
      _userinfo = decodeIfNeeded(slice(start, cursor - 1), firstPercent - start, charset)
      true
    }
  }

  def hostAndPort =
    host && {
      val mark = cursor
      ch(':') && port || reset(mark)
    }

  def host = {
    val mark = cursor
    (`IP-literal` || reset(mark) && IPv4address) && {
      val c = current
      is(c, COLON | SLASH) || c == EOI // only accept IP literal if it's followed by ':', '/' or EOI
    } || reset(mark) && `reg-name`
  }

  def port = {
    if (Digit) {
      var value: Int = previous - '0'
      if (Digit) {
        value = 10 * value + previous - '0'
        if (Digit) {
          value = 10 * value + previous - '0'
          if (Digit) {
            value = 10 * value + previous - '0'
            if (Digit)
              value = 10 * value + previous - '0'
          }
        }
      }
      _port = value
    }
    true
  }

  def `IP-literal` =
    ch('[') && {
      IPv6address
      // we disable the IPvFuture matching for the time being
      //val mark = cursor
      //(IPv6address || reset(mark) && IPvFuture)
    } && ch(']')

  def IPv6address = {
    def h16c = h16 && ch(':') && current != ':'
    def cc = ch(':') && ch(':')
    def optH16c = { val mark = cursor; h16c || reset(mark) }
    val mark = cursor
    (h16c && h16c && h16c && h16c && h16c && h16c && ls32
      || reset(mark) && cc && h16c && h16c && h16c && h16c && h16c && ls32
      || reset(mark) && (h16 || reset(mark)) && cc && h16c && h16c && h16c && h16c && ls32
      || reset(mark) && (optH16c && h16 || reset(mark)) && cc && h16c && h16c && h16c && ls32
      || reset(mark) && (optH16c && optH16c && h16 || reset(mark)) && cc && h16c && h16c && ls32
      || reset(mark) && (optH16c && optH16c && optH16c && h16 || reset(mark)) && cc && h16c && ls32
      || reset(mark) && (optH16c && optH16c && optH16c && optH16c && h16 || reset(mark)) && cc && ls32
      || reset(mark) && (optH16c && optH16c && optH16c && optH16c && optH16c && h16 || reset(mark)) && cc && h16
      || reset(mark) && (optH16c && optH16c && optH16c && optH16c && optH16c && optH16c && h16 || reset(mark)) && cc) && {
        _host = IPv6Host(slice(mark, cursor))
        true
      }
  }

  def h16 = Hexdig && (Hexdig && (Hexdig && (Hexdig || true) || true) || true)

  def ls32 = {
    val mark = cursor
    h16 && ch(':') && h16 || reset(mark) && IPv4address
  }

  def IPv4address = {
    val start = cursor
    `dec-octet` && ch('.') && `dec-octet` && ch('.') && `dec-octet` && ch('.') && `dec-octet` && {
      _host = IPv4Host(slice(start, cursor))
      true
    }
  }

  def `dec-octet` = {
    val mark = cursor
    (reset(mark) && ch('1') && Digit && Digit
      || reset(mark) && ch('2') && matches(DIGIT04) && Digit
      || reset(mark) && ch('2') && ch('5') && matches(DIGIT05)
      || reset(mark) && matches(DIGIT19) && Digit
      || reset(mark) && Digit)
  }

  def `reg-name` = {
    val start = cursor
    var mark = start
    resetFirstUpper() && resetFirstPercent()
    while (matches(UNRESERVED & ~UPPER_ALPHA | SUB_DELIM) || UpperAlpha || `pct-encoded`) mark = cursor
    reset(mark)
    // host is always decoded using UTF-8 (http://tools.ietf.org/html/rfc3986#section-3.2.2)
    _host = NamedHost {
      if (cursor > start) {
        firstUpper -= start
        firstPercent -= start
        val s = slice(start, cursor)
        if (firstUpper >= 0)
          if (firstPercent >= 0) toLowerIfNeeded(decodeIfNeeded(s, firstPercent, charset), math.min(firstPercent, firstUpper))
          else toLowerIfNeeded(s, firstUpper)
        else if (firstPercent >= 0) toLowerIfNeeded(decodeIfNeeded(s, firstPercent, charset), firstPercent) else s
      } else ""
    }
    true
  }

  def `path-abempty` = {
    val start = cursor
    slashSegments && savePath(start)
  }

  def `path-absolute` = {
    val start = cursor
    ch('/') && {
      val mark = cursor
      `segment-nz` && slashSegments || reset(mark)
    } && savePath(start)
  }

  def `path-noscheme` = {
    val start = cursor
    `segment-nz-nc` && slashSegments && savePath(start)
  }

  def `path-rootless` = {
    val start = cursor
    `segment-nz` && slashSegments && savePath(start)
  }

  def `path-empty` = true

  def segment = {
    var mark = cursor
    while (pchar) mark = cursor
    reset(mark)
  }

  def `segment-nz` = pchar && segment

  def `segment-nz-nc` = {
    val start = cursor
    var mark = start
    while (matches(UNRESERVED | SUB_DELIM | AT) || `pct-encoded`) mark = cursor
    cursor > start && reset(mark)
  }

  def pchar = matches(PARSE_PATH_SEGMENT_CHAR) || `pct-encoded`

  def query = {
    def part = {
      val start = cursor
      var mark = cursor
      resetFirstPercent()
      while (matches(PARSE_QUERY_CHAR) || `pct-encoded`) mark = cursor
      reset(mark)
      if (cursor > start)
        decodeIfNeeded(slice(start, cursor).replace('+', ' '), firstPercent - start, charset)
      else ""
    }
    // non-tail recursion, which we accept because it allows us to directly build the query
    // without having to reverse it at the end.
    // Also: request query usually do not have hundreds of elements, so we should get away with
    // putting some pressure onto the JVM stack
    def readKVP(): Query = {
      val key = part
      val value = if (ch('=')) part else Query.EmptyValue
      val tail = if (ch('&')) readKVP() else Query.Empty
      Query.Cons(key, value, tail)
    }
    _query =
      if (mode == Uri.ParsingMode.RelaxedWithRawQuery) {
        val start = cursor
        while (is(current, PARSE_QUERY_CHAR)) advance()
        if (cursor > start) Query.Raw(slice(start, cursor)) else Query.Empty
      } else readKVP()
    true
  }

  def fragment = {
    val start = cursor
    var mark = cursor
    resetFirstPercent()
    while (matches(PARSE_FRAGMENT_CHAR) || `pct-encoded`) mark = cursor
    reset(mark)
    _fragment = Some {
      if (cursor > start) decodeIfNeeded(slice(start, cursor), firstPercent - start, charset)
      else ""
    }
    true
  }

  def `pct-encoded` = ch('%') && Hexdig && Hexdig && markPercent(-3)

  //////////////////////////// ADDITIONAL HTTP-SPECIFIC RULES //////////////////////////

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-22#section-2.7
  def `absolute-path` = {
    val start = cursor
    ch('/') && segment && slashSegments && savePath(start)
  }

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-22#section-5.3
  def `request-target` = {
    val start = cursor
    (`absolute-path` && { val mark = cursor; ch('?') && query || reset(mark) } // origin-form
      || reset(start) && `absolute-URI` // absolute-form
      || reset(start) && authority) // authority-form or asterisk-form
  }

  def parseHttpRequestTarget(): Uri = {
    complete("request-target", `request-target`)
    val path = if (_scheme.isEmpty) _path else collapseDotSegments(_path)
    create(_scheme, _userinfo, _host, _port, path, _query, _fragment)
  }

  /////////////// REQUIRED RFC 2234 (ABNF) CORE RULES ////////////////

  def Alpha = matches(LOWER_ALPHA) || UpperAlpha
  def UpperAlpha = is(current, UPPER_ALPHA) && markUpper() && advance()
  def Digit = matches(DIGIT)
  def Hexdig = matches(HEX_DIGIT)

  ////////////// HELPER RULES ////////////////

  def matches(mask: Int) = is(current, mask) && advance()

  def slashSegments = {
    var mark = cursor
    while (ch('/') && segment) mark = cursor
    reset(mark)
  }

  ////////////// HELPERS ////////////////

  def previous: Char = input.charAt(cursor - 1)

  def current: Char = if (cursor < input.length) input.charAt(cursor) else EOI

  def ch(c: Char) = (current == c) && advance()

  def reset(mark: Int) = { cursor = mark; true }

  def advance() = { cursor += 1; maxCursor = math.max(maxCursor, cursor); true }

  def savePath(start: Int) = {
    _path = Path(slice(start, cursor), charset)
    true
  }

  def slice(start: Int, end: Int): String = input.sliceString(start, end)

  def resetFirstUpper() = { firstUpper = -1; true }
  def resetFirstPercent() = { firstPercent = -1; true }
  def markUpper() = { if (firstUpper == -1) firstUpper = cursor; true }
  def markPercent(delta: Int = 0) = { if (firstPercent == -1) firstPercent = cursor + delta; true }

  def toLowerIfNeeded(s: String, firstUpper: Int) = {
    @tailrec def lower(sb: JStringBuilder): String =
      if (sb.length == s.length) sb.toString
      else lower(sb.append(toLowerCase(s.charAt(sb.length))))
    if (firstUpper >= 0) lower(new JStringBuilder(s.length).append(s, 0, firstUpper)) else s
  }
  def decodeIfNeeded(s: String, firstPercent: Int, charset: Charset) =
    if (firstPercent >= 0) decode(s, charset, firstPercent)() else s

  def complete(target: String, matched: Boolean): Unit =
    if (!matched || cursor < input.length) {
      val sb = new JStringBuilder().append("Illegal ").append(target).append(", unexpected ")
      if (maxCursor < input.length) {
        sb.append("character '")
        val c = input.charAt(maxCursor)
        if (Character.isISOControl(c)) sb.append("\\u%04x" format c.toInt) else sb.append(c)
        sb.append('\'')
      } else sb.append("end-of-input")
      val summary = sb.append(" at position ").append(maxCursor).toString

      sb.setLength(0)
      val detail = sb.append('\n').append(input.toString.map(c ⇒ if (Character.isISOControl(c)) '?' else c).mkString(""))
        .append('\n').append(" " * maxCursor).append("^\n").toString
      fail(summary, detail)
    }
}

// TODO: switch to CharMask-based masking
private[http] object UriParser {
  // compile time constants
  final val EOI = '\uffff'

  final val LOWER_ALPHA = 0x0001
  final val UPPER_ALPHA = 0x0002
  final val ALPHA = LOWER_ALPHA | UPPER_ALPHA
  final val DIGIT19 = 0x0004
  final val DIGIT04 = 0x0008
  final val DIGIT05 = 0x0010
  final val DIGIT = DIGIT19 | DIGIT04 | DIGIT05
  final val LOWER_HEX_LETTER = 0x0020
  final val UPPER_HEX_LETTER = 0x0040
  final val HEX_LETTER = LOWER_HEX_LETTER | UPPER_HEX_LETTER
  final val HEX_DIGIT = DIGIT | HEX_LETTER
  final val UNRESERVED_SPECIALS = 0x0080
  final val UNRESERVED = ALPHA | DIGIT | UNRESERVED_SPECIALS | DASH
  final val GEN_DELIM = 0x0100
  final val SUB_DELIM_BASE = 0x0200
  final val AT = 0x0400
  final val COLON = 0x0800
  final val SLASH = 0x1000
  final val QUESTIONMARK = 0x2000
  final val PLUS = 0x4000
  final val DASH = 0x8000
  final val DOT = 0x10000
  final val AMP = 0x20000
  final val EQUAL = 0x40000
  final val SPACE = 0x80000
  final val SUB_DELIM = SUB_DELIM_BASE | AMP | EQUAL | PLUS
  final val HASH = 0x100000
  final val RESERVED = GEN_DELIM | SUB_DELIM | QUESTIONMARK | COLON | SLASH | HASH | AT

  final val OTHER_VCHAR = 0x200000
  final val PERCENT = 0x400000
  final val VCHAR = OTHER_VCHAR | UNRESERVED | HEX_DIGIT | RESERVED | AT | COLON | SLASH | QUESTIONMARK | DASH | DOT | HASH | PERCENT

  // FRAGMENT/QUERY and PATH characters have two classes of acceptable characters: one that strictly
  // follows rfc3986, which should be used for rendering urls, and one relaxed, which accepts all visible
  // 7-bit ASCII characters, even if they're not percent-encoded.

  final val QUERY_FRAGMENT_CHAR = UNRESERVED | SUB_DELIM | COLON | AT | SLASH | QUESTIONMARK
  final val PATH_SEGMENT_CHAR = UNRESERVED | SUB_DELIM | COLON | AT

  final val RELAXED_FRAGMENT_CHAR = VCHAR & ~PERCENT
  final val RELAXED_PATH_SEGMENT_CHAR = VCHAR & ~(PERCENT | SLASH | QUESTIONMARK | HASH)
  final val RELAXED_QUERY_CHAR = VCHAR & ~(PERCENT | AMP | EQUAL | HASH)
  final val RAW_QUERY_CHAR = VCHAR & ~HASH

  private[this] val props = new Array[Int](128)

  private[http] def is(c: Int, mask: Int): Boolean = (props(indexFor(c)) & mask) != 0
  private def indexFor(c: Int): Int = c & sex(c - 128) // branchless for `if (c < 128) c else 0`
  private def mark(mask: Int, chars: Char*): Unit = chars.foreach(c ⇒ props(indexFor(c)) = props(indexFor(c)) | mask)
  private def mark(mask: Int, range: NumericRange[Char]): Unit = mark(mask, range.toSeq: _*)

  mark(LOWER_ALPHA, 'a' to 'z')
  mark(UPPER_ALPHA, 'A' to 'Z')
  mark(DIGIT19, '1' to '9')
  mark(DIGIT04, '0' to '4')
  mark(DIGIT05, '0' to '5')
  mark(LOWER_HEX_LETTER, 'a' to 'f')
  mark(UPPER_HEX_LETTER, 'A' to 'F')
  mark(UNRESERVED_SPECIALS, '.', '_', '~')
  mark(GEN_DELIM, '[', ']')
  mark(SUB_DELIM_BASE, '!', '$', '\'', '(', ')', '*', ',', ';')
  mark(AT, '@')
  mark(COLON, ':')
  mark(SLASH, '/')
  mark(QUESTIONMARK, '?')
  mark(PLUS, '+')
  mark(DASH, '-')
  mark(DOT, '.')
  mark(AMP, '&')
  mark(EQUAL, '=')
  mark(SPACE, ' ')
  mark(HASH, '#')
  mark(PERCENT, '%')

  mark(OTHER_VCHAR, '<', '>', '\\', '^', '`', '{', '|', '}')

  def toLowerCase(c: Char): Char = if (is(c, UPPER_ALPHA)) (c + 0x20).toChar else c
  def toUpperCase(c: Char): Char = if (is(c, LOWER_ALPHA)) (c - 0x20).toChar else c

  /////////////////// BRANCHLESS HELPERS ////////////////

  def sex(i: Int): Int = i >> 31 // sign-extend, branchless version of `if (i < 0) 0xFFFFFFFF else 0x00000000`

  def abs(i: Int): Int = { val j = sex(i); (i ^ j) - j }

  def hexDigit(i: Int): Char = { val j = i & 0x0F; ('0' + j + -7 * sex(0x7ffffff6 + j)).toChar }

  def hexValue(c: Char): Int = (c & 0x1f) + ((c >> 6) * 0x19) - 0x10
}
