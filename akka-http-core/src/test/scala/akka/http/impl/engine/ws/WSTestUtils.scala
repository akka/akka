/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.impl.engine.ws.Protocol.Opcode
import akka.util.ByteString

import scala.util.Random

object WSTestUtils {
  def frameHeader(
    opcode: Opcode,
    length: Long,
    fin:    Boolean,
    mask:   Option[Int] = None,
    rsv1:   Boolean     = false,
    rsv2:   Boolean     = false,
    rsv3:   Boolean     = false): ByteString = {
    def set(should: Boolean, mask: Int): Int =
      if (should) mask else 0

    val flags =
      set(fin, Protocol.FIN_MASK) |
        set(rsv1, Protocol.RSV1_MASK) |
        set(rsv2, Protocol.RSV2_MASK) |
        set(rsv3, Protocol.RSV3_MASK)

    val opcodeByte = opcode.code | flags

    require(length >= 0)
    val (lengthByteComponent, lengthBytes) =
      if (length < 126) (length.toByte, ByteString.empty)
      else if (length < 65536) (126.toByte, shortBE(length.toInt))
      else throw new IllegalArgumentException("Only lengths < 65536 allowed in test")

    val maskMask = if (mask.isDefined) Protocol.MASK_MASK else 0
    val maskBytes = mask match {
      case Some(mask) ⇒ intBE(mask)
      case None       ⇒ ByteString.empty
    }
    val lengthByte = lengthByteComponent | maskMask
    ByteString(opcodeByte.toByte, lengthByte.toByte) ++ lengthBytes ++ maskBytes
  }
  def frame(opcode: Opcode, data: ByteString, fin: Boolean, mask: Boolean): ByteString =
    if (mask) {
      val mask = Random.nextInt()
      frameHeader(opcode, data.size, fin, mask = Some(mask)) ++
        maskedBytes(data, mask)._1
    } else
      frameHeader(opcode, data.size, fin, mask = None) ++ data

  def closeFrame(closeCode: Int, mask: Boolean, msg: String = ""): ByteString =
    closeFrame(closeCode, mask, ByteString(msg, "UTF-8"))

  def closeFrame(closeCode: Int, mask: Boolean, msgBytes: ByteString): ByteString =
    frame(Opcode.Close, closeFrameData(closeCode, msgBytes), fin = true, mask)

  def closeFrameData(closeCode: Int, msgBytes: ByteString = ByteString.empty): ByteString =
    shortBE(closeCode) ++ msgBytes

  def maskedASCII(str: String, mask: Int): (ByteString, Int) =
    FrameEventParser.mask(ByteString(str, "ASCII"), mask)
  def maskedUTF8(str: String, mask: Int): (ByteString, Int) =
    FrameEventParser.mask(ByteString(str, "UTF-8"), mask)
  def maskedBytes(bytes: ByteString, mask: Int): (ByteString, Int) =
    FrameEventParser.mask(bytes, mask)

  def shortBE(value: Int): ByteString = {
    require(value >= 0 && value < 65536, s"Value wasn't in short range: $value")
    ByteString(
      ((value >> 8) & 0xff).toByte,
      ((value >> 0) & 0xff).toByte)
  }
  def intBE(value: Int): ByteString =
    ByteString(
      ((value >> 24) & 0xff).toByte,
      ((value >> 16) & 0xff).toByte,
      ((value >> 8) & 0xff).toByte,
      ((value >> 0) & 0xff).toByte)
}
