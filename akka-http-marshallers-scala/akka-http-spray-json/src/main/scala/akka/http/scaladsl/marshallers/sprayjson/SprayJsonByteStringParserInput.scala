/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import java.nio.charset.StandardCharsets

import akka.util.ByteString
import spray.json.ParserInput.IndexedBytesParserInput

/**
 * INTERNAL API
 *
 * ParserInput reading directly off a ByteString. (Based on the ByteArrayBasedParserInput)
 * that avoids a separate decoding step.
 *
 * TODO: make private before next major version
 */
@deprecated("Will be made private.", "10.0.2")
final class SprayJsonByteStringParserInput(bytes: ByteString) extends IndexedBytesParserInput {
  protected def byteAt(offset: Int): Byte = bytes(offset)

  override def length: Int = bytes.size
  override def sliceString(start: Int, end: Int): String =
    bytes.slice(start, end - start).decodeString(StandardCharsets.UTF_8)
  override def sliceCharArray(start: Int, end: Int): Array[Char] =
    StandardCharsets.UTF_8.decode(bytes.slice(start, end).asByteBuffer).array()
}

@deprecated("Not needed any more. Should have been private.", "10.0.2")
object SprayJsonByteStringParserInput {
  private final val EOI = '\uFFFF'
  // compile-time constant
  private final val ErrorChar = '\uFFFD' // compile-time constant, universal UTF-8 replacement character '�'
}
