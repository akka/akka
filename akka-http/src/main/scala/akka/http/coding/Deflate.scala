/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import java.io.OutputStream
import java.util.zip.{ DataFormatException, ZipException, Inflater, Deflater }
import akka.util.{ ByteStringBuilder, ByteString }

import scala.annotation.tailrec
import akka.http.util._
import akka.http.model._
import headers.HttpEncodings

class Deflate(val messageFilter: HttpMessage ⇒ Boolean) extends Coder {
  val encoding = HttpEncodings.deflate
  def newCompressor = new DeflateCompressor
  def newDecompressor = new DeflateDecompressor
}

/**
 * An encoder and decoder for the HTTP 'deflate' encoding.
 */
object Deflate extends Deflate(Encoder.DefaultFilter) {
  def apply(messageFilter: HttpMessage ⇒ Boolean) = new Deflate(messageFilter)
}

class DeflateCompressor extends Compressor {
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
    assert(deflater.needsInput())
    deflater.setInput(input.toArray)
    drain(buffer)
  }
  protected def flushWithBuffer(buffer: Array[Byte]): ByteString = {
    // trick the deflater into flushing: switch compression level
    // FIXME: use proper APIs and SYNC_FLUSH when Java 6 support is dropped
    deflater.deflate(EmptyByteArray, 0, 0)
    deflater.setLevel(Deflater.NO_COMPRESSION)
    val res1 = drain(buffer)
    deflater.setLevel(Deflater.BEST_COMPRESSION)
    val res2 = drain(buffer)
    res1 ++ res2
  }
  protected def finishWithBuffer(buffer: Array[Byte]): ByteString = {
    deflater.finish()
    val res = drain(buffer)
    deflater.end()
    res
  }

  @tailrec
  protected final def drain(buffer: Array[Byte], result: ByteStringBuilder = new ByteStringBuilder()): ByteString = {
    val len = deflater.deflate(buffer)
    if (len > 0) {
      result ++= ByteString.fromArray(buffer, 0, len)
      drain(buffer, result)
    } else {
      assert(deflater.needsInput())
      result.result()
    }
  }

  private def newTempBuffer(size: Int = 65536): Array[Byte] =
    // The default size is somewhat arbitrary, we'd like to guess a better value but Deflater/zlib
    // is buffering in an unpredictable manner.
    // `compress` will only return any data if the buffered compressed data has some size in
    // the region of 10000-50000 bytes.
    // `flush` and `finish` will return any size depending on the previous input.
    // This value will hopefully provide a good compromise between memory churn and
    // excessive fragmentation of ByteStrings.
    new Array[Byte](size)
}

class DeflateDecompressor extends Decompressor {
  protected lazy val inflater = new Inflater()

  def decompress(buffer: ByteString): ByteString =
    try {
      inflater.setInput(buffer.toArray)
      drain(new Array[Byte](buffer.length * 2))
    } catch {
      case e: DataFormatException ⇒
        throw new ZipException(e.getMessage.toOption getOrElse "Invalid ZLIB data format")
    }

  @tailrec protected final def drain(buffer: Array[Byte], result: ByteString = ByteString.empty): ByteString = {
    val len = inflater.inflate(buffer)
    if (len > 0) drain(buffer, result ++ ByteString.fromArray(buffer, 0, len))
    else if (inflater.needsDictionary) throw new ZipException("ZLIB dictionary missing")
    else result
  }
}
