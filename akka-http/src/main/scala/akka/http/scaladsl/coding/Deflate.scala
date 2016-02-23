/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import java.util.zip.{ Inflater, Deflater }
import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import ByteStringParser.{ ParseResult, ParseStep }
import akka.util.{ ByteStringBuilder, ByteString }

import scala.annotation.tailrec
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings

class Deflate(val messageFilter: HttpMessage ⇒ Boolean) extends Coder with StreamDecoder {
  val encoding = HttpEncodings.deflate
  def newCompressor = new DeflateCompressor
  def newDecompressorStage(maxBytesPerChunk: Int) = () ⇒ new DeflateDecompressor(maxBytesPerChunk)
}
object Deflate extends Deflate(Encoder.DefaultFilter)

class DeflateCompressor extends Compressor {
  import DeflateCompressor._

  protected lazy val deflater = new Deflater(Deflater.BEST_COMPRESSION, false)

  override final def compressAndFlush(input: ByteString): ByteString = {
    val buffer = newTempBuffer(input.size)

    compressWithBuffer(input, buffer) ++ flushWithBuffer(buffer)
  }
  override final def compressAndFinish(input: ByteString): ByteString = {
    val buffer = newTempBuffer(input.size)

    compressWithBuffer(input, buffer) ++ finishWithBuffer(buffer)
  }
  override final def compress(input: ByteString): ByteString = compressWithBuffer(input, newTempBuffer())
  override final def flush(): ByteString = flushWithBuffer(newTempBuffer())
  override final def finish(): ByteString = finishWithBuffer(newTempBuffer())

  protected def compressWithBuffer(input: ByteString, buffer: Array[Byte]): ByteString = {
    require(deflater.needsInput())
    deflater.setInput(input.toArray)
    drainDeflater(deflater, buffer)
  }
  protected def flushWithBuffer(buffer: Array[Byte]): ByteString = {
    val written = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH)
    ByteString.fromArray(buffer, 0, written)
  }
  protected def finishWithBuffer(buffer: Array[Byte]): ByteString = {
    deflater.finish()
    val res = drainDeflater(deflater, buffer)
    deflater.end()
    res
  }

  private def newTempBuffer(size: Int = 65536): Array[Byte] = {
    // The default size is somewhat arbitrary, we'd like to guess a better value but Deflater/zlib
    // is buffering in an unpredictable manner.
    // `compress` will only return any data if the buffered compressed data has some size in
    // the region of 10000-50000 bytes.
    // `flush` and `finish` will return any size depending on the previous input.
    // This value will hopefully provide a good compromise between memory churn and
    // excessive fragmentation of ByteStrings.
    // We also make sure that buffer size stays within a reasonable range, to avoid
    // draining deflator with too small buffer.
    new Array[Byte](math.max(size, MinBufferSize))
  }
}

private[http] object DeflateCompressor {
  val MinBufferSize = 1024

  @tailrec
  def drainDeflater(deflater: Deflater, buffer: Array[Byte], result: ByteStringBuilder = new ByteStringBuilder()): ByteString = {
    val len = deflater.deflate(buffer)
    if (len > 0) {
      result ++= ByteString.fromArray(buffer, 0, len)
      drainDeflater(deflater, buffer, result)
    } else {
      require(deflater.needsInput())
      result.result()
    }
  }
}

class DeflateDecompressor(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault) extends DeflateDecompressorBase(maxBytesPerChunk) {

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

abstract class DeflateDecompressorBase(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault)
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
