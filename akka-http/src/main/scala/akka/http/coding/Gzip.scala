/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import java.io.OutputStream
import java.util.zip.{ Inflater, CRC32, ZipException, Deflater }
import akka.util.ByteString

import scala.annotation.tailrec
import akka.http.model._
import headers.HttpEncodings

import scala.util.control.NoStackTrace

class Gzip(val messageFilter: HttpMessage ⇒ Boolean) extends Decoder with Encoder {
  val encoding = HttpEncodings.gzip
  def newCompressor = new GzipCompressor
  def newDecompressor = new GzipDecompressor
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
  override protected def finishWithBuffer(buffer: Array[Byte]): ByteString = super.finishWithBuffer(buffer) ++ trailer()

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

/** A suspendable gzip decompressor */
class GzipDecompressor extends DeflateDecompressor {
  override protected lazy val inflater = new Inflater(true) // disable ZLIB headers
  override def decompress(input: ByteString): ByteString = DecompressionStateMachine.run(input)

  import GzipDecompressor._
  object DecompressionStateMachine extends StateMachine {
    def initialState = readHeaders

    private def readHeaders(data: ByteString): Action =
      // header has at least size 3
      if (data.size < 4) SuspendAndRetryWithMoreData
      else try {
        val reader = new ByteReader(data)
        import reader._

        if (readByte() != 0x1F || readByte() != 0x8B) fail("Not in GZIP format") // check magic header
        if (readByte() != 8) fail("Unsupported GZIP compression method") // check compression method
        val flags = readByte()
        skip(6) // skip MTIME, XFL and OS fields
        if ((flags & 4) > 0) skip(readShort()) // skip optional extra fields
        if ((flags & 8) > 0) while (readByte() != 0) {} // skip optional file name
        if ((flags & 16) > 0) while (readByte() != 0) {} // skip optional file comment
        if ((flags & 2) > 0 && crc16(data.take(currentOffset)) != readShort()) fail("Corrupt GZIP header")

        ContinueWith(deflate(new CRC32), remainingData)
      } catch {
        case ByteReader.NeedMoreData ⇒ SuspendAndRetryWithMoreData
      }

    private def deflate(checkSum: CRC32)(data: ByteString): Action = {
      assert(inflater.needsInput())
      inflater.setInput(data.toArray)
      val output = drain(new Array[Byte](data.length * 2))
      checkSum.update(output.toArray)
      if (inflater.finished()) EmitAndContinueWith(output, readTrailer(checkSum), data.takeRight(inflater.getRemaining))
      else EmitAndSuspend(output)
    }

    private def readTrailer(checkSum: CRC32)(data: ByteString): Action =
      try {
        val reader = new ByteReader(data)
        import reader._

        if (readInt() != checkSum.getValue.toInt) fail("Corrupt data (CRC32 checksum error)")
        if (readInt() != inflater.getBytesWritten.toInt /* truncated to 32bit */ ) fail("Corrupt GZIP trailer ISIZE")

        inflater.reset()
        checkSum.reset()
        ContinueWith(initialState, remainingData) // start over to support multiple concatenated gzip streams
      } catch {
        case ByteReader.NeedMoreData ⇒ SuspendAndRetryWithMoreData
      }

    private def fail(msg: String) = Fail(new ZipException(msg))

    private def crc16(data: ByteString) = {
      val crc = new CRC32
      crc.update(data.toArray)
      crc.getValue.toInt & 0xFFFF
    }
  }
}

/** INTERNAL API */
private[http] object GzipDecompressor {
  // RFC 1952: http://tools.ietf.org/html/rfc1952 section 2.2
  val Header = ByteString(
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

  class ByteReader(input: ByteString) {
    import ByteReader.NeedMoreData

    private[this] var off = 0

    def readByte(): Int =
      if (off < input.length) {
        val x = input(off)
        off += 1
        x.toInt & 0xFF
      } else throw NeedMoreData
    def readShort(): Int = readByte() | (readByte() << 8)
    def readInt(): Int = readShort() | (readShort() << 16)
    def skip(numBytes: Int): Unit =
      if (off + numBytes <= input.length) off += numBytes
      else throw NeedMoreData
    def currentOffset: Int = off
    def remainingData: ByteString = input.drop(off)
  }
  object ByteReader {
    val NeedMoreData = new Exception with NoStackTrace
  }

  /** A simple state machine implementation for suspendable parsing */
  trait StateMachine {
    sealed trait Action
    /** Cache the current input and suspend to wait for more data */
    case object SuspendAndRetryWithMoreData extends Action
    /** Emit some output and suspend in the current state and wait for more data */
    case class EmitAndSuspend(output: ByteString) extends Action
    /** Proceed to the nextState and immediately run it with the remainingInput */
    case class ContinueWith(nextState: State, remainingInput: ByteString) extends Action
    /** Emit some output and then proceed to the nextState and immediately run it with the remainingInput */
    case class EmitAndContinueWith(output: ByteString, nextState: State, remainingInput: ByteString) extends Action
    /** Fail with the given exception and go into the failed state which will throw for any new data */
    case class Fail(cause: Throwable) extends Action

    type State = ByteString ⇒ Action
    def initialState: State

    private[this] var state: State = initialState

    /** Run the state machine with the current input */
    @tailrec final def run(input: ByteString, result: ByteString = ByteString.empty): ByteString =
      state(input) match {
        case SuspendAndRetryWithMoreData ⇒
          val oldState = state
          state = { newData ⇒
            state = oldState
            oldState(input ++ newData)
          }
          result
        case EmitAndSuspend(output) ⇒ result ++ output
        case ContinueWith(next, remainingInput) ⇒
          state = next
          run(remainingInput, result)
        case EmitAndContinueWith(output, next, remainingInput) ⇒
          state = next
          run(remainingInput, result ++ output)
        case Fail(cause) ⇒
          state = failState
          throw cause
      }

    private def failState: State = _ ⇒ throw new IllegalStateException("Trying to reuse failed decompressor.")
  }
}
