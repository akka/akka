/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import java.math.BigInteger

object ByteArrayPrettyPrinter {

  private val toHex = Array.tabulate(256) { i ⇒
    (if (i < 16) "0" else "") +
      BigInteger.valueOf(i.asInstanceOf[Long]).toString(16).toUpperCase
  }

  def prettyPrint(bytes: Array[Byte], maximumLength: Int = 32, detectionPrefix: Int = 32): String = {
    val ellipsis = if (bytes.length <= maximumLength) "" else s" ... (and ${bytes.length - maximumLength} bytes)"
    val prefix = math.min(bytes.length, maximumLength)

    if (Utf8Verifier.isUtf8Text(bytes, detectionPrefix)) {
      val lengthLimited = (new String(bytes, 0, prefix, "UTF-8"))
      val b = new StringBuilder
      b.append("[\"")
        .append(lengthLimited)
        .append("\"")
        .append(ellipsis)
        .append("]")
        .toString()
    } else {
      val b = new StringBuilder

      b.append("[")
      for (i ← 0 until prefix) {
        b.append(toHex(bytes(i)))
        if (i < prefix - 1) b.append(" ")
      }
      b.append(ellipsis)
        .append("]")
        .toString
    }
  }

}

/**
 * UTF-8 (and US-ASCII) detector for arrays of bytes.
 */
object Utf8Verifier {

  private val Start = 0.asInstanceOf[Byte]
  private val Mismatch = 1.asInstanceOf[Byte]

  /**
   * Detects if the given bytes can be interpreted as a proper UTF-8 encoded string.
   *
   * @param bytes The array of bytes to be checked
   * @param checkPrefixLength Number of bytes to limit the search to
   * @return true if the bytes can be interpreted as a UTF-8 string
   */
  def isUtf8Text(bytes: Array[Byte], checkPrefixLength: Int): Boolean = {
    val prefixLength = math.min(bytes.length, checkPrefixLength)
    var state = Start
    var i = 0

    while (state != Mismatch && i < prefixLength) {
      state = getNextState(bytes(i), state)
      i += 1
    }

    state != Mismatch
  }

  /*
   * Magic comes below.
   *
   * This code is a heavily simplified and compressed code inspired by the Java port (http://jchardet.sourceforge.net/) of
   * the Mozilla charset detector (http://www-archive.mozilla.org/projects/intl/chardet.html). This code attempts to
   * detect UTF-8 only.
   *
   * FIXME: If this goes in, licensing information should be provided (MPL)
   */
  private val cclass: Array[Int] = Array(
    286331153, 1118481, 286331153, 286327057, 286331153, 286331153, 286331153, 286331153, 286331153, 286331153,
    286331153, 286331153, 286331153, 286331153, 286331153, 286331153, 858989090, 1145324612, 1145324612, 1145324612,
    1431655765, 1431655765, 1431655765, 1431655765, 1717986816, 1717986918, 1717986918, 1717986918, -2004318073,
    -2003269496, -1145324614, 16702940)
  private val states: Array[Int] = Array(
    -1408167679, 878082233, 286331153, 286331153, 572662306, 572662306, 290805009, 286331153, 290803985, 286331153,
    293041937, 286331153, 293015825, 286331153, 295278865, 286331153, 294719761, 286331153, 298634257, 286331153,
    297865489, 286331153, 287099921, 286331153, 285212689, 286331153)

  private def getNextState(inputByte: Byte, lastState: Byte): Byte =
    (0xFF & ((states(((lastState * 16 + ((cclass((inputByte & 0xFF) >> 3) >> ((inputByte & 7) << 2)) & 0x0000000F)) & 0xFF) >> 3) >>
      ((((lastState * 16 + ((cclass((inputByte & 0xFF) >> 3) >> ((inputByte & 7) << 2)) & 0x0000000F)) & 0xFF) & 7) << 2)) & 0x0000000F)).asInstanceOf[Byte]

}
