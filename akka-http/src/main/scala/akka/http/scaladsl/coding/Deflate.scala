/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import java.util.zip.{ Deflater, Inflater }

import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import ByteStringParser.{ ParseResult, ParseStep }
import akka.annotation.InternalApi
import akka.util.{ ByteString, ByteStringBuilder }

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

/** Internal API */
@InternalApi
private[coding] object DeflateCompressor {
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

/** Internal API */
@InternalApi
private[coding] class DeflateDecompressor(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault) extends DeflateDecompressorBase(maxBytesPerChunk) {

  override def createLogic(attr: Attributes) = new ParsingLogic {
    /** Step that probes if the deflate stream contains a zlib wrapper */
    case object ProbeWrapping extends ParseStep[ByteString] {
      override def onTruncation(): Unit = completeStage()

      override def parse(reader: ByteStringParser.ByteReader): ParseResult[ByteString] = {
        val inflater = examineAndBuildInflater(reader.remainingData)
        ParseResult(None, new Inflate(inflater, noPostProcessing = true, ProbeWrapping))
      }
    }

    /**
     * We examine the head byte of the incoming data stream, looking for the standard 0x8 in the first 4 bits of the header
     * that marks a byte stream as using a wrapped deflate stream. If we don't find that header, we fall back to the no-wrap
     * inflater.
     *
     * Wrapped deflate streams usually start with the first byte being 0x78 == b01111000. The lower three bits == 000 would mean an
     * uncompressed stream for an unwrapped deflate stream. However, in that case the remaining bits are undefined and
     * would likely be set to zero as well. Therefore, checking the lower 4 bits to be b1000 seems to be sufficient to
     * decide that a stream is wrapped.
     *
     * More details on the structure of wrap and no-wrap headers of Deflate can be found at:
     * https://www.ietf.org/rfc/rfc1950.txt (wrap) and
     * https://www.ietf.org/rfc/rfc1951.txt (no-wrap)
     */
    private def examineAndBuildInflater(bytes: ByteString): Inflater = {
      val wrapped = (bytes.head & 0x0F) == 0x08
      new Inflater(!wrapped)
    }

    startWith(ProbeWrapping)
  }
}

/** Internal API */
@InternalApi
private[coding] abstract class DeflateDecompressorBase(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault)
  extends ByteStringParser[ByteString] {

  class Inflate(inflater: Inflater, noPostProcessing: Boolean, afterInflate: ParseStep[ByteString]) extends ParseStep[ByteString] {
    protected def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit = {}

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
