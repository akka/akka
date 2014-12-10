/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import akka.util.ByteString

import scala.util.control.NoStackTrace

/**
 * A helper class to read from a ByteString statefully.
 *
 * INTERNAL API
 */
private[akka] class ByteReader(input: ByteString) {
  import ByteReader.NeedMoreData

  private[this] var off = 0

  def currentOffset: Int = off
  def remainingData: ByteString = input.drop(off)
  def fromStartToHere: ByteString = input.take(currentOffset)

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
  def skipZeroTerminatedString(): Unit = while (readByte() != 0) {}
}

/*
* INTERNAL API
*/
private[akka] object ByteReader {
  val NeedMoreData = new Exception with NoStackTrace
}