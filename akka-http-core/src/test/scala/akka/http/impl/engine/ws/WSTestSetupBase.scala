/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.impl.engine.ws.Protocol.Opcode
import akka.http.impl.engine.ws.WSTestUtils._
import akka.util.ByteString
import org.scalatest.Matchers

import scala.util.Random

trait WSTestSetupBase extends Matchers {
  def send(bytes: ByteString): Unit
  def expectBytes(length: Int): ByteString
  def expectBytes(bytes: ByteString): Unit

  def sendWSFrame(
    opcode: Opcode,
    data:   ByteString,
    fin:    Boolean,
    mask:   Boolean    = false,
    rsv1:   Boolean    = false,
    rsv2:   Boolean    = false,
    rsv3:   Boolean    = false): Unit = {
    val (theMask, theData) =
      if (mask) {
        val m = Random.nextInt()
        (Some(m), maskedBytes(data, m)._1)
      } else (None, data)
    send(frameHeader(opcode, data.length, fin, theMask, rsv1, rsv2, rsv3) ++ theData)
  }

  def sendWSCloseFrame(closeCode: Int, mask: Boolean = false): Unit =
    send(closeFrame(closeCode, mask))

  def expectWSFrame(
    opcode: Opcode,
    data:   ByteString,
    fin:    Boolean,
    mask:   Option[Int] = None,
    rsv1:   Boolean     = false,
    rsv2:   Boolean     = false,
    rsv3:   Boolean     = false): Unit =
    expectBytes(frameHeader(opcode, data.length, fin, mask, rsv1, rsv2, rsv3) ++ data)

  def expectWSCloseFrame(closeCode: Int, mask: Boolean = false): Unit =
    expectBytes(closeFrame(closeCode, mask))

  def expectNetworkData(length: Int): ByteString = expectBytes(length)
  def expectNetworkData(data: ByteString): Unit = expectBytes(data)

  def expectFrameOnNetwork(opcode: Opcode, data: ByteString, fin: Boolean): Unit = {
    expectFrameHeaderOnNetwork(opcode, data.size, fin)
    expectNetworkData(data)
  }
  def expectMaskedFrameOnNetwork(opcode: Opcode, data: ByteString, fin: Boolean): Unit = {
    val Some(mask) = expectFrameHeaderOnNetwork(opcode, data.size, fin)
    val masked = maskedBytes(data, mask)._1
    expectNetworkData(masked)
  }

  def expectMaskedCloseFrame(closeCode: Int): Unit =
    expectMaskedFrameOnNetwork(Protocol.Opcode.Close, closeFrameData(closeCode), fin = true)

  /** Returns the mask if any is available */
  def expectFrameHeaderOnNetwork(opcode: Opcode, length: Long, fin: Boolean): Option[Int] = {
    val (op, l, f, m) = expectFrameHeaderOnNetwork()
    op shouldEqual opcode
    l shouldEqual length
    f shouldEqual fin
    m
  }
  def expectFrameHeaderOnNetwork(): (Opcode, Long, Boolean, Option[Int]) = {
    val header = expectNetworkData(2)

    val fin = (header(0) & Protocol.FIN_MASK) != 0
    val op = header(0) & Protocol.OP_MASK

    val hasMask = (header(1) & Protocol.MASK_MASK) != 0
    val length7 = header(1) & Protocol.LENGTH_MASK
    val length = length7 match {
      case 126 ⇒
        val length16Bytes = expectNetworkData(2)
        (length16Bytes(0) & 0xff) << 8 | (length16Bytes(1) & 0xff) << 0
      case 127 ⇒
        val length64Bytes = expectNetworkData(8)
        (length64Bytes(0) & 0xff).toLong << 56 |
          (length64Bytes(1) & 0xff).toLong << 48 |
          (length64Bytes(2) & 0xff).toLong << 40 |
          (length64Bytes(3) & 0xff).toLong << 32 |
          (length64Bytes(4) & 0xff).toLong << 24 |
          (length64Bytes(5) & 0xff).toLong << 16 |
          (length64Bytes(6) & 0xff).toLong << 8 |
          (length64Bytes(7) & 0xff).toLong << 0
      case x ⇒ x
    }
    val mask =
      if (hasMask) {
        val maskBytes = expectNetworkData(4)
        val mask =
          (maskBytes(0) & 0xff) << 24 |
            (maskBytes(1) & 0xff) << 16 |
            (maskBytes(2) & 0xff) << 8 |
            (maskBytes(3) & 0xff) << 0
        Some(mask)
      } else None

    (Opcode.forCode(op.toByte), length, fin, mask)
  }
}
