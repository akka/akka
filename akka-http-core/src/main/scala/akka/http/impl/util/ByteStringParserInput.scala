/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.util

import java.nio.charset.StandardCharsets

import akka.parboiled2.ParserInput.DefaultParserInput
import akka.util.ByteString

/**
 * ParserInput reading directly off a ByteString. (Based on the ByteArrayBasedParserInput)
 * This avoids a separate decoding step but assumes that each byte represents exactly one character,
 * which is encoded by ISO-8859-1!
 * You can therefore use this ParserInput type only if you know that all input will be `ISO-8859-1`-encoded,
 * or only contains 7-bit ASCII characters (which is a subset of ISO-8859-1)!
 *
 * Note that this ParserInput type will NOT work with general `UTF-8`-encoded input as this can contain
 * character representations spanning multiple bytes. However, if you know that your input will only ever contain
 * 7-bit ASCII characters (0x00-0x7F) then UTF-8 is fine, since the first 127 UTF-8 characters are
 * encoded with only one byte that is identical to 7-bit ASCII and ISO-8859-1.
 */
final class ByteStringParserInput(bytes: ByteString) extends DefaultParserInput {
  override def charAt(ix: Int): Char = (bytes(ix) & 0xFF).toChar
  override def length: Int = bytes.size
  override def sliceString(start: Int, end: Int): String = bytes.slice(start, end).decodeString(StandardCharsets.ISO_8859_1)
  override def sliceCharArray(start: Int, end: Int): Array[Char] =
    StandardCharsets.ISO_8859_1.decode(bytes.slice(start, end).asByteBuffer).array()
}
