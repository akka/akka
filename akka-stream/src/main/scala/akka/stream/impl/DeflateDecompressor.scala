/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.zip.Inflater

import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import akka.stream.impl.io.ByteStringParser.{ ParseResult, ParseStep }
import akka.util.ByteString

class DeflateDecompressor(maxBytesPerChunk: Int = DeflateDecompressorBase.MaxBytesPerChunkDefault)
  extends DeflateDecompressorBase(maxBytesPerChunk) {

  override def createLogic(attr: Attributes) = new DecompressorParsingLogic {
    override val inflater: Inflater = new Inflater()

    override val inflateState = new Inflate(true) {
      override def onTruncation(): Unit = completeStage()
    }

    override def afterInflate = inflateState
    override def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit = {}

    startWith(inflateState)
  }
}

abstract class DeflateDecompressorBase(maxBytesPerChunk: Int = DeflateDecompressorBase.MaxBytesPerChunkDefault)
  extends ByteStringParser[ByteString] {

  abstract class DecompressorParsingLogic extends ParsingLogic {
    val inflater: Inflater
    def afterInflate: ParseStep[ByteString]
    def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit
    val inflateState: Inflate

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
  }
}

private[akka] object DeflateDecompressorBase {
  final val MaxBytesPerChunkDefault = 64 * 1024
}
