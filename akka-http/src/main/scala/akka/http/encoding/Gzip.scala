/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.encoding

import java.util.zip.{ Inflater, CRC32, ZipException, Deflater }
import scala.annotation.tailrec
import akka.http.model._
import headers.HttpEncodings

class Gzip(val messageFilter: HttpMessage ⇒ Boolean) extends Decoder with Encoder {
  val encoding = HttpEncodings.gzip
  def newCompressor = new GzipCompressor
  def newDecompressor = new GzipDecompressor
}

/**
 * An encoder and decoder for the HTTP 'gzip' encoding.
 */
object Gzip extends Gzip(Encoder.DefaultFilter) {
  def apply(messageFilter: MessagePredicate) = new Gzip(messageFilter)
}

object GzipCompressor {
  // RFC 1952: http://tools.ietf.org/html/rfc1952 section 2.2
  val Header = Array[Byte](
    31, // ID1
    -117, // ID2
    8, // CM = Deflate
    0, // FLG
    0, // MTIME 1
    0, // MTIME 2
    0, // MTIME 3
    0, // MTIME 4
    0, // XFL
    0 // OS
    )
}

class GzipCompressor extends DeflateCompressor {
  override protected lazy val deflater = new Deflater(Deflater.BEST_COMPRESSION, true)
  private val checkSum = new CRC32 // CRC32 of uncompressed data
  private var headerSent = false

  override def compress(buffer: Array[Byte]) = {
    if (!headerSent) {
      output.write(GzipCompressor.Header)
      headerSent = true
    }
    checkSum.update(buffer)
    super.compress(buffer)
  }

  override def finish() = {
    def byte(i: Int) = (i & 0xFF).asInstanceOf[Byte]
    val crc = checkSum.getValue.asInstanceOf[Int]
    val tot = deflater.getTotalIn
    deflater.finish()
    drain()
    deflater.end()
    output.write(byte(crc)); output.write(byte(crc >> 8)); output.write(byte(crc >> 16)); output.write(byte(crc >> 24));
    output.write(byte(tot)); output.write(byte(tot >> 8)); output.write(byte(tot >> 16)); output.write(byte(tot >> 24));
    getBytes
  }
}

class GzipDecompressor extends DeflateDecompressor {
  override protected lazy val inflater = new Inflater(true)
  private val checkSum = new CRC32 // CRC32 of uncompressed data
  private var headerRead = false

  override protected def decompress(buffer: Array[Byte], offset: Int) = {
    decomp(buffer, offset, throw _)
  }

  @tailrec
  private def decomp(buffer: Array[Byte], offset: Int, produceResult: Exception ⇒ Int): Int = {
    var off = offset
    def fail(msg: String) = throw new ZipException(msg)
    def readByte(): Int = {
      if (off < buffer.length) {
        val x = buffer(off)
        off += 1
        x.toInt & 0xFF
      } else fail(s"Unexpected end of data offset: $off length: ${buffer.length}")
    }
    def readShort(): Int = readByte() | (readByte() << 8)
    def readInt(): Int = readShort() | (readShort() << 16)
    def readHeader(): Unit = {
      def crc16(buffer: Array[Byte], offset: Int, len: Int) = {
        val crc = new CRC32
        crc.update(buffer, offset, len)
        crc.getValue.asInstanceOf[Int] & 0xFFFF
      }
      if (readByte() != 0x1F || readByte() != 0x8B) fail("Not in GZIP format") // check magic header
      if (readByte() != 8) fail("Unsupported GZIP compression method") // check compression method
      val flags = readByte()
      off += 6 // skip MTIME, XFL and OS fields
      if ((flags & 4) > 0) off += readShort() // skip optional extra fields
      if ((flags & 8) > 0) while (readByte() != 0) {} // skip optional file name
      if ((flags & 16) > 0) while (readByte() != 0) {} // skip optional file comment
      if ((flags & 2) > 0 && crc16(buffer, offset, off - offset) != readShort()) fail("Corrupt GZIP header")
    }
    def readTrailer(): Unit = {
      if (readInt() != checkSum.getValue.asInstanceOf[Int]) fail("Corrupt data (CRC32 checksum error)")
      if (readInt() != inflater.getBytesWritten) fail("Corrupt GZIP trailer ISIZE")
    }

    var recurse = false
    try {
      if (!headerRead) {
        readHeader()
        headerRead = true
      }
      val dataStart = output.pos
      off = super.decompress(buffer, off)
      checkSum.update(output.buffer, dataStart, output.pos - dataStart)
      if (inflater.finished()) {
        readTrailer()
        recurse = true
        inflater.reset()
        checkSum.reset()
        headerRead = false
      }
    } catch {
      case e: Exception ⇒ produceResult(e)
    }

    if (recurse && off < buffer.length) {
      val mark = output.pos
      decomp(buffer, off, _ ⇒ { output.resetTo(mark); off })
    } else off
  }

}