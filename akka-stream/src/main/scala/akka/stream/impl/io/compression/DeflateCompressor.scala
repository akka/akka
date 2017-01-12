/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io.compression

import java.util.zip.Deflater

import akka.util.{ ByteString, ByteStringBuilder }

import scala.annotation.tailrec

/** INTERNAL API */
private[akka] class DeflateCompressor extends Compressor {
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

  def close(): Unit = deflater.end()

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

/** INTERNAL API */
private[akka] object DeflateCompressor {
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
