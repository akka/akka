/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

/** INTERNAL API */
private[http] final class ByteFlag(val value: Int) extends AnyVal {
  def |(that: ByteFlag): ByteFlag = new ByteFlag((this.value | that.value).toByte)
  def &(that: ByteFlag): ByteFlag = new ByteFlag((this.value | that.value).toByte)

  def isSet(byteFlag: ByteFlag): Boolean = (byteFlag.value & value) != 0
  def ifSet(flag: Boolean): ByteFlag = if (flag) this else ByteFlag.Zero
  override def toString: String = s"ByteFlag(${Integer.toHexString(value)})"
}
/** INTERNAL API */
private[impl] object ByteFlag {
  val Zero = new ByteFlag(0)

  def binaryLeftPad(byte: Byte): String = {
    val string = Integer.toBinaryString(byte)
    val pad = "0" * (8 - string.length) // leftPad
    pad + string
  }
}
