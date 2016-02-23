/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.stream.stage._
import akka.util.{ ByteStringBuilder, ByteString }

/**
 * A utf16 (= Java char) to utf8 encoder.
 *
 * INTERNAL API
 */
private[http] class Utf8Encoder extends PushStage[String, ByteString] {
  import Utf8Encoder._

  var surrogateValue: Int = 0
  def inSurrogatePair: Boolean = surrogateValue != 0

  def onPush(input: String, ctx: Context[ByteString]): SyncDirective = {
    val builder = new ByteStringBuilder

    def b(v: Int): Unit = {
      builder += v.toByte
    }

    def step(char: Int): Unit =
      if (!inSurrogatePair)
        if (char <= Utf8OneByteLimit) builder += char.toByte
        else if (char <= Utf8TwoByteLimit) {
          b(0xc0 | ((char & 0x7c0) >> 6)) // upper 5 bits
          b(0x80 | (char & 0x3f)) // lower 6 bits
        } else if (char >= SurrogateFirst && char < SurrogateSecond)
          surrogateValue = 0x10000 | ((char ^ SurrogateFirst) << 10)
        else if (char >= SurrogateSecond && char < 0xdfff)
          throw new IllegalArgumentException(f"Unexpected UTF-16 surrogate continuation")
        else if (char <= Utf8ThreeByteLimit) {
          b(0xe0 | ((char & 0xf000) >> 12)) // upper 4 bits
          b(0x80 | ((char & 0x0fc0) >> 6)) // middle 6 bits
          b(0x80 | (char & 0x3f)) // lower 6 bits
        } else
          throw new IllegalStateException("Char cannot be >= 2^16") // char value was converted from 16bit value
      else if (char >= SurrogateSecond && char <= 0xdfff) {
        surrogateValue |= (char & 0x3ff)
        b(0xf0 | ((surrogateValue & 0x1c0000) >> 18)) // upper 3 bits
        b(0x80 | ((surrogateValue & 0x3f000) >> 12)) // first middle 6 bits
        b(0x80 | ((surrogateValue & 0x0fc0) >> 6)) // second middle 6 bits
        b(0x80 | (surrogateValue & 0x3f)) // lower 6 bits
        surrogateValue = 0
      } else throw new IllegalArgumentException(f"Expected UTF-16 surrogate continuation")

    var offset = 0
    while (offset < input.length) {
      step(input(offset))
      offset += 1
    }

    if (builder.length > 0) ctx.push(builder.result())
    else ctx.pull()
  }

  override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective =
    if (inSurrogatePair) ctx.fail(new IllegalArgumentException("Truncated String input (ends in the middle of surrogate pair)"))
    else super.onUpstreamFinish(ctx)
}

/**
 * INTERNAL API
 */
private[http] object Utf8Encoder {
  val SurrogateFirst = 0xd800
  val SurrogateSecond = 0xdc00

  val Utf8OneByteLimit = lowerNBitsSet(7)
  val Utf8TwoByteLimit = lowerNBitsSet(11)
  val Utf8ThreeByteLimit = lowerNBitsSet(16)

  def lowerNBitsSet(n: Int): Long = (1L << n) - 1
}