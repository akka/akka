/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import scala.annotation.tailrec
import scala.util.{ Try, Failure, Success }
import akka.util.ByteString
import akka.parboiled2
import parboiled2._

object BitBuilder {
  implicit class BitBuilderContext(val ctx: StringContext) {
    def b(args: Any*): ByteString = {
      val input = ctx.parts.mkString.replace("\r\n", "\n")
      val parser = new BitSpecParser(input)
      val bits = parser.parseBits()
      bits.get.toByteString
    }
  }
}

final case class Bits(elements: Seq[Bits.BitElement]) {
  def toByteString: ByteString = {
    import Bits._

    val bits = elements.map(_.bits).sum

    require(bits % 8 == 0)
    val data = new Array[Byte](bits / 8)
    @tailrec def rec(byteIdx: Int, bitIdx: Int, remaining: Seq[Bits.BitElement]): Unit =
      if (bitIdx >= 8) rec(byteIdx + 1, bitIdx - 8, remaining)
      else remaining match {
        case Zero +: rest ⇒
          // zero by default
          rec(byteIdx, bitIdx + 1, rest)
        case One +: rest ⇒
          data(byteIdx) = (data(byteIdx) | (1 << (7 - bitIdx))).toByte
          rec(byteIdx, bitIdx + 1, rest)
        case Multibit(bits, value) +: rest ⇒
          val numBits = math.min(8 - bitIdx, bits)
          val remainingBits = bits - numBits
          val highestNBits = value >> remainingBits
          val lowestNBitMask = (~(0xff << numBits) & 0xff)
          data(byteIdx) = (data(byteIdx) | (highestNBits & lowestNBitMask)).toByte

          if (remainingBits > 0)
            rec(byteIdx + 1, 0, Multibit(remainingBits, value) +: rest)
          else
            rec(byteIdx, bitIdx + numBits, rest)
        case Nil ⇒
          require(bitIdx == 0 && byteIdx == bits / 8)
      }
    rec(0, 0, elements)

    ByteString(data) // this could be ByteString1C
  }
}
object Bits {
  sealed trait BitElement {
    def bits: Int
  }
  sealed abstract class SingleBit extends BitElement {
    def bits: Int = 1
  }
  case object Zero extends SingleBit
  case object One extends SingleBit
  case class Multibit(bits: Int, value: Long) extends BitElement
}

class BitSpecParser(val input: ParserInput) extends parboiled2.Parser {
  import Bits._
  def parseBits(): Try[Bits] =
    bits.run() match {
      case s: Success[Bits]       ⇒ s
      case Failure(e: ParseError) ⇒ Failure(new RuntimeException(formatError(e, new ErrorFormatter(showTraces = true))))
      case _                      ⇒ throw new IllegalStateException()
    }

  def bits: Rule1[Bits] = rule { zeroOrMore(element) ~ EOI ~> (Bits(_)) }

  val WSChar = CharPredicate(' ', '\t', '\n')
  def ws = rule { zeroOrMore(wsElement) }
  def wsElement = rule { WSChar | comment }
  def comment =
    rule {
      '#' ~ zeroOrMore(!'\n' ~ ANY) ~ '\n'
    }

  def element: Rule1[BitElement] = rule {
    zero | one | multi
  }
  def zero: Rule1[BitElement] = rule { '0' ~ push(Zero) ~ ws }
  def one: Rule1[BitElement] = rule { '1' ~ push(One) ~ ws }
  def multi: Rule1[Multibit] = rule {
    capture(oneOrMore('x' ~ ws)) ~> (_.count(_ == 'x')) ~ '=' ~ value ~ ws ~> Multibit
  }
  def value: Rule1[Long] = rule {
    capture(oneOrMore(CharPredicate.HexDigit)) ~> ((str: String) ⇒ java.lang.Long.parseLong(str, 16))
  }
}