package akka.http.model.parser

import language.implicitConversions
import java.nio.charset.Charset
import akka.http.util.UTF8

sealed abstract class ParserInput {
  def charAt(ix: Int): Char
  def length: Int
  def sliceString(start: Int, end: Int): String
  override def toString: String = sliceString(0, length)
}

// bimorphic ParserInput implementation
// Note: make sure to not add another implementation, otherwise usage of this class
// might turn megamorphic at the call-sites thereby effectively disabling method inlining!
object ParserInput {
  implicit def apply(bytes: Array[Byte]): ParserInput = apply(bytes, UTF8)
  def apply(bytes: Array[Byte], charset: Charset): ParserInput =
    new ParserInput {
      def charAt(ix: Int) = bytes(ix).toChar
      def length = bytes.length
      def sliceString(start: Int, end: Int) = if (end > start) new String(bytes, start, end - start, charset) else ""
    }
  implicit def apply(string: String): ParserInput =
    new ParserInput {
      def charAt(ix: Int) = string.charAt(ix)
      def length = string.length
      def sliceString(start: Int, end: Int) = if (end > start) string.substring(start, end) else ""
    }
  implicit def apply(chars: Array[Char]): ParserInput = apply(new String(chars))
}
