/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import scala.util.control.NoStackTrace
import akka.util.ByteString

/**
 * A helper class to read from a ByteString statefully.
 *
 * INTERNAL API
 */
private[akka] class ByteReader(input: ByteString) {
  import ByteReader.NeedMoreData

  private[this] var off = 0

  def hasRemaining: Boolean = off < input.size

  def currentOffset: Int = off
  def remainingData: ByteString = input.drop(off)
  def fromStartToHere: ByteString = input.take(currentOffset)

  def readByte(): Int =
    if (off < input.length) {
      val x = input(off)
      off += 1
      x.toInt & 0xFF
    } else throw NeedMoreData
  def readShortLE(): Int = readByte() | (readByte() << 8)
  def readIntLE(): Int = readShortLE() | (readShortLE() << 16)
  def readLongLE(): Long = (readIntBE() & 0xffffffffL) | ((readIntLE() & 0xffffffffL) << 32)

  def readShortBE(): Int = (readByte() << 8) | readByte()
  def readIntBE(): Int = (readShortBE() << 16) | readShortBE()
  def readLongBE(): Long = ((readIntBE() & 0xffffffffL) << 32) | (readIntBE() & 0xffffffffL)

  def skip(numBytes: Int): Unit =
    if (off + numBytes <= input.length) off += numBytes
    else throw NeedMoreData
  def skipZeroTerminatedString(): Unit = while (readByte() != 0) {}
}

/*
* INTERNAL API
*/
private[akka] object ByteReader {
  val NeedMoreData = new Exception with NoStackTrace
}