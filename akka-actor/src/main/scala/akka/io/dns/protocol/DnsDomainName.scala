/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 * Adopted from Apache v2 licensed: https://github.com/ilya-epifanov/akka-dns
 */

package akka.io.dns.protocol

import akka.util.{ ByteIterator, ByteString, ByteStringBuilder }

object DnsDomainName {
  def length(name: String): Short = {
    (name.length + 2).toShort
  }

  def write(it: ByteStringBuilder, name: String) {
    for (label ← name.split('.')) {
      it.putByte(label.length.toByte)
      for (c ← label) {
        it.putByte(c.toByte)
      }
    }
    it.putByte(0)
  }

  def parse(it: ByteIterator, msg: ByteString): String = {
    val ret = StringBuilder.newBuilder
    //    ret.sizeHint(getNameLength(it.clone(), 0))
    //    println("Parsing name")
    while (true) {
      val length = it.getByte
      //      println(s"Label length: $length")

      if (length == 0) {
        val r = ret.result()
        //        println(s"Name: $r")
        return r
      }

      if (ret.nonEmpty)
        ret.append('.')

      if ((length & 0xc0) == 0xc0) {
        val offset = ((length.toShort & 0x3f) << 8) | (it.getByte.toShort & 0x00ff)
        //        println(s"Computed offset: $offset")
        return ret.result() + parse(msg.iterator.drop(offset), msg)
      }

      ret.appendAll(it.clone().take(length).map(_.toChar))
      it.drop(length)
    }
    ??? // FIXME!!!!!!!!!!
  }
}
