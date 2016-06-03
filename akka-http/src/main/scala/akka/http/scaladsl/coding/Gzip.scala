/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import java.util.zip.{ CRC32, Deflater, Inflater, ZipException }

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import ByteStringParser.{ ParseResult, ParseStep }
import akka.util.ByteString

class Gzip(val messageFilter: HttpMessage ⇒ Boolean) extends Coder with StreamDecoder {
  val encoding = HttpEncodings.gzip
  def newCompressor = new GzipCompressor
  def newDecompressorStage(maxBytesPerChunk: Int) = () ⇒ new GzipDecompressor(maxBytesPerChunk)
}

/**
 * An encoder and decoder for the HTTP 'gzip' encoding.
 */
object Gzip extends Gzip(Encoder.DefaultFilter) {
  def apply(messageFilter: HttpMessage ⇒ Boolean) = new Gzip(messageFilter)
}

class GzipCompressor extends DeflateCompressor {
  override protected lazy val deflater = new Deflater(Deflater.BEST_COMPRESSION, true)
  private val checkSum = new CRC32 // CRC32 of uncompressed data
  private var headerSent = false
  private var bytesRead = 0L

  override protected def compressWithBuffer(input: ByteString, buffer: Array[Byte]): ByteString = {
    updateCrc(input)
    header() ++ super.compressWithBuffer(input, buffer)
  }
  override protected def flushWithBuffer(buffer: Array[Byte]): ByteString = header() ++ super.flushWithBuffer(buffer)
  override protected def finishWithBuffer(buffer: Array[Byte]): ByteString = header() ++ super.finishWithBuffer(buffer) ++ trailer()

  private def updateCrc(input: ByteString): Unit = {
    checkSum.update(input.toArray)
    bytesRead += input.length
  }
  private def header(): ByteString =
    if (!headerSent) {
      headerSent = true
      GzipDecompressor.Header
    } else ByteString.empty

  private def trailer(): ByteString = {
    def int32(i: Int): ByteString = ByteString(i, i >> 8, i >> 16, i >> 24)
    val crc = checkSum.getValue.toInt
    val tot = bytesRead.toInt // truncated to 32bit as specified in https://tools.ietf.org/html/rfc1952#section-2
    val trailer = int32(crc) ++ int32(tot)

    trailer
  }
}

class GzipDecompressor(maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault) extends DeflateDecompressorBase(maxBytesPerChunk) {
  override def createLogic(attr: Attributes) = new DecompressorParsingLogic {
    override val inflater: Inflater = new Inflater(true)
    override def afterInflate: ParseStep[ByteString] = ReadTrailer
    override def afterBytesRead(buffer: Array[Byte], offset: Int, length: Int): Unit =
      crc32.update(buffer, offset, length)

    trait Step extends ParseStep[ByteString] {
      override def onTruncation(): Unit = failStage(new ZipException("Truncated GZIP stream"))
    }
    override val inflateState = new Inflate(false) with Step
    startWith(ReadHeaders)

    /** Reading the header bytes */
    case object ReadHeaders extends Step {
      override def parse(reader: ByteStringParser.ByteReader): ParseResult[ByteString] = {
        import reader._
        if (readByte() != 0x1F || readByte() != 0x8B) fail("Not in GZIP format") // check magic header
        if (readByte() != 8) fail("Unsupported GZIP compression method") // check compression method
        val flags = readByte()
        skip(6) // skip MTIME, XFL and OS fields
        if ((flags & 4) > 0) skip(readShortLE()) // skip optional extra fields
        if ((flags & 8) > 0) skipZeroTerminatedString() // skip optional file name
        if ((flags & 16) > 0) skipZeroTerminatedString() // skip optional file comment
        if ((flags & 2) > 0 && crc16(fromStartToHere) != readShortLE()) fail("Corrupt GZIP header")

        inflater.reset()
        crc32.reset()
        ParseResult(None, inflateState, false)
      }
    }
    var crc32: CRC32 = new CRC32
    private def fail(msg: String) = throw new ZipException(msg)

    /** Reading the trailer */
    case object ReadTrailer extends Step {
      override def parse(reader: ByteStringParser.ByteReader): ParseResult[ByteString] = {
        import reader._
        if (readIntLE() != crc32.getValue.toInt) fail("Corrupt data (CRC32 checksum error)")
        if (readIntLE() != inflater.getBytesWritten.toInt /* truncated to 32bit */ )
          fail("Corrupt GZIP trailer ISIZE")
        ParseResult(None, ReadHeaders, true)
      }
    }
  }
  private def crc16(data: ByteString) = {
    val crc = new CRC32
    crc.update(data.toArray)
    crc.getValue.toInt & 0xFFFF
  }
}

/** INTERNAL API */
private[http] object GzipDecompressor {
  // RFC 1952: http://tools.ietf.org/html/rfc1952 section 2.2
  val Header = ByteString(
    0x1F, // ID1
    0x8B, // ID2
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
