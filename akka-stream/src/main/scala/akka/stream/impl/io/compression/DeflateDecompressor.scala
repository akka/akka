/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io.compression

import java.util.zip.Inflater

import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import akka.stream.impl.io.ByteStringParser.{ ParseResult, ParseStep }
import akka.util.ByteString

/** INTERNAL API */
private[akka] class DeflateDecompressor(maxBytesPerChunk: Int = DeflateDecompressorBase.MaxBytesPerChunkDefault)
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

