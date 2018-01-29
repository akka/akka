/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import java.nio.charset.StandardCharsets

import akka.annotation.InternalApi
import akka.util.ByteString
import spray.json.ParserInput.IndexedBytesParserInput

/**
 * INTERNAL API
 *
 * ParserInput reading directly off a ByteString. (Based on the ByteArrayBasedParserInput)
 * that avoids a separate decoding step.
 *
 */
@InternalApi
private[sprayjson] final class SprayJsonByteStringParserInput(bytes: ByteString) extends IndexedBytesParserInput {
  protected def byteAt(offset: Int): Byte = bytes(offset)

  override def length: Int = bytes.size
  override def sliceString(start: Int, end: Int): String =
    bytes.slice(start, end - start).decodeString(StandardCharsets.UTF_8)
  override def sliceCharArray(start: Int, end: Int): Array[Char] =
    StandardCharsets.UTF_8.decode(bytes.slice(start, end).asByteBuffer).array()
}
