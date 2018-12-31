/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io.compression

import java.util.zip.Inflater

import akka.annotation.InternalApi
import akka.stream.impl.io.ByteStringParser
import akka.stream.impl.io.ByteStringParser.{ ParseResult, ParseStep }
import akka.util.ByteString

/** INTERNAL API */
@InternalApi private[akka] abstract class DeflateDecompressorBase(maxBytesPerChunk: Int)
  extends ByteStringParser[ByteString] {

  abstract class DecompressorParsingLogic extends ParsingLogic {
    val inflater: Inflater
    def afterInflate: ParseStep[ByteString]
    def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit
    def inflating: Inflate

    abstract class Inflate(noPostProcessing: Boolean) extends ParseStep[ByteString] {
      override def canWorkWithPartialData = true
      override def parse(reader: ByteStringParser.ByteReader): ParseResult[ByteString] = {
        inflater.setInput(reader.remainingData.toArray)

        val buffer = new Array[Byte](maxBytesPerChunk)
        val read = inflater.inflate(buffer)

        reader.skip(reader.remainingSize - inflater.getRemaining)

        if (read > 0) {
          afterBytesRead(buffer, 0, read)
          val next = if (inflater.finished()) afterInflate else this
          ParseResult(Some(ByteString.fromArray(buffer, 0, read)), next, noPostProcessing)
        } else {
          if (inflater.finished()) ParseResult(None, afterInflate, noPostProcessing)
          else throw ByteStringParser.NeedMoreData
        }
      }
    }

    override def postStop(): Unit = inflater.end()
  }
}

/** INTERNAL API */
@InternalApi private[akka] object DeflateDecompressorBase
