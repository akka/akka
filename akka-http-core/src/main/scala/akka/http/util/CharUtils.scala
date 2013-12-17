package akka.http.util

// FIXME: remove/replace with parboiled2 utility functions
/** Various basic utils for working with characters */
object CharUtils {
  def hexValue(c: Char): Int = (c & 0x1f) + ((c >> 6) * 0x19) - 0x10

  def lowerHexDigit(long: Long): Char = lowerHexDigit_internal((long & 0x0FL).toInt)
  def lowerHexDigit(int: Int): Char = lowerHexDigit_internal(int & 0x0F)
  private def lowerHexDigit_internal(i: Int) = (48 + i + (39 & ((9 - i) >> 31))).toChar

  def upperHexDigit(long: Long): Char = upperHexDigit_internal((long & 0x0FL).toInt)
  def upperHexDigit(int: Int): Char = upperHexDigit_internal(int & 0x0F)
  private def upperHexDigit_internal(i: Int) = (48 + i + (7 & ((9 - i) >> 31))).toChar

  def toLowerCase(c: Char): Char = if (CharPredicate.UpperAlpha(c)) (c + 0x20).toChar else c

  def abs(i: Int): Int = { val j = i >> 31; (i ^ j) - j }

  def escape(c: Char): String = c match {
    case '\t'                           ⇒ "\\t"
    case '\r'                           ⇒ "\\r"
    case '\n'                           ⇒ "\\n"
    case x if Character.isISOControl(x) ⇒ "\\u%04x" format c.toInt
    case x                              ⇒ x.toString
  }
}
