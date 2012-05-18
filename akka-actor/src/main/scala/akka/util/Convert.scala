/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util
//FIXME DOCS!
object Convert {

  def intToBytes(value: Int): Array[Byte] = {
    val bytes = Array.fill[Byte](4)(0)
    bytes(0) = (value >>> 24).asInstanceOf[Byte]
    bytes(1) = (value >>> 16).asInstanceOf[Byte]
    bytes(2) = (value >>> 8).asInstanceOf[Byte]
    bytes(3) = value.asInstanceOf[Byte]
    bytes
  }

  def bytesToInt(bytes: Array[Byte], offset: Int): Int = {
    (0 until 4).foldLeft(0)((value, index) ⇒ value + ((bytes(index + offset) & 0x000000FF) << ((4 - 1 - index) * 8)))
  }

  def longToBytes(value: Long): Array[Byte] = {
    val writeBuffer = Array.fill[Byte](8)(0)
    writeBuffer(0) = (value >>> 56).asInstanceOf[Byte]
    writeBuffer(1) = (value >>> 48).asInstanceOf[Byte]
    writeBuffer(2) = (value >>> 40).asInstanceOf[Byte]
    writeBuffer(3) = (value >>> 32).asInstanceOf[Byte]
    writeBuffer(4) = (value >>> 24).asInstanceOf[Byte]
    writeBuffer(5) = (value >>> 16).asInstanceOf[Byte]
    writeBuffer(6) = (value >>> 8).asInstanceOf[Byte]
    writeBuffer(7) = (value >>> 0).asInstanceOf[Byte]
    writeBuffer
  }

  def bytesToLong(buf: Array[Byte]): Long = {
    ((buf(0) & 0xFFL) << 56) |
      ((buf(1) & 0xFFL) << 48) |
      ((buf(2) & 0xFFL) << 40) |
      ((buf(3) & 0xFFL) << 32) |
      ((buf(4) & 0xFFL) << 24) |
      ((buf(5) & 0xFFL) << 16) |
      ((buf(6) & 0xFFL) << 8) |
      ((buf(7) & 0xFFL) << 0)
  }
}
