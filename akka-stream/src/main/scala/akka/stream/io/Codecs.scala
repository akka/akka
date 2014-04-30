/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.util.ByteString
import java.nio.ByteOrder
import akka.stream.io.Codecs._
import scala.annotation.tailrec

trait FieldDecoder[T] {
  import Decoder._
  def context: DecodeContext
  def andThen: T ⇒ DecodeStep
  def decode: DecodeStep
}

trait DecodeContext {
  import Decoder._
  def read(count: ⇒ Int)(andThen: ReadAction): DecodeStep
  def read(atLeast: ⇒ Int, atMost: ⇒ Int)(andThen: ReadAction): DecodeStep
  def pushBack(count: ⇒ Int): Unit
  def decode[T](decoder: FieldDecoder[T]): DecodeStep
}

object Decoder {
  type ReadAction = (ByteString, Int, Int) ⇒ DecodeStep

  // TODO: try to make it private
  case class DecodeStep(preamble: () ⇒ Unit, step: ReadAction)
}

abstract class Decoder[Out] extends DecodeContext {
  import Decoder._

  private var buffer = ByteString.empty
  private var readIdx = 0
  private var readTarget = 0
  private var minimumReadTarget = 0
  // TODO: make it pull based
  private var results: List[Out] = Nil
  private var compactionMarker = 0
  def start: DecodeStep

  private var currentStep: DecodeStep = _

  implicit protected def context: DecodeContext = this

  final def parseFragment(fragment: ByteString): List[Out] = {
    // Lazily initialize the first step
    if (currentStep eq null) {
      currentStep = start
      currentStep.preamble()
    }
    if (results.nonEmpty) results = Nil
    buffer ++= fragment

    while (buffer.size >= minimumReadTarget) {
      readTarget = math.min(readTarget, buffer.size)
      minimumReadTarget = readTarget
      currentStep = currentStep.step(buffer, readIdx, readTarget)
      currentStep.preamble()
    }

    results.reverse
  }

  final private def nextStep(atLeastBytes: ⇒ Int, atMostBytes: ⇒ Int, step: ReadAction): DecodeStep = {
    DecodeStep(
      () ⇒ {
        val available = readTarget
        readTarget = available + atMostBytes
        minimumReadTarget = available + atLeastBytes
      },
      (buf, start, end) ⇒ {
        val next = step(buf, start, end)
        readIdx = math.min(readTarget, buffer.size)
        readTarget = readIdx
        minimumReadTarget = readIdx
        next
      })
  }

  final override def read(count: ⇒ Int)(andThen: ReadAction): DecodeStep = nextStep(count, count, andThen)
  final override def read(atLeast: ⇒ Int, atMost: ⇒ Int)(andThen: ReadAction): DecodeStep = nextStep(atLeast, atMost, andThen)

  final override def pushBack(count: ⇒ Int): Unit = {
    val pb = count
    if (pb > readTarget)
      throw new IllegalArgumentException("Cannot push back more bytes than was read from the previous step. " +
        s"Attempted to push back [$pb] while only [$readTarget] is avalaible.")
    readTarget -= pb
    minimumReadTarget -= pb
    readIdx = math.min(readTarget, buffer.size) - pb
  }

  final override def decode[T](decoder: FieldDecoder[T]): DecodeStep = decoder.decode

  final protected def emit(out: Out): DecodeStep = {
    emitIntermediate(out)
    start
  }

  final protected def emitIntermediate(out: Out): Unit = {
    results = out :: results
    slideAndCompact()
    readTarget = 0
    minimumReadTarget = 0
    readIdx = 0
  }

  private def slideAndCompact(): Unit = {
    buffer = buffer.drop(readTarget)
    compactionMarker -= readTarget
    if (compactionMarker < 0) {
      buffer = buffer.compact
      compactionMarker = buffer.size
    }
  }
}

object Codecs {
  import Decoder.DecodeStep

  // TODO: endian support
  case class IntField(andThen: Int ⇒ DecodeStep)(implicit val context: DecodeContext) extends FieldDecoder[Int] {
    val decode: DecodeStep = context.read(4) { (buf, start, end) ⇒
      andThen(buf.slice(start, end).iterator.getInt(ByteOrder.BIG_ENDIAN))
    }
    override def toString = "[32 bit Integer field]"
  }

  case class VarintField(andThen: Int ⇒ DecodeStep)(implicit val context: DecodeContext) extends FieldDecoder[Int] {
    private def unpackFirst(first: Byte): Int = first.toInt & 0x3f
    private def unpack(prev: Int, next: Byte): Int = (prev << 8) + (next.toInt & 0xff)

    val decode = context.read(1) { (buf, start, end) ⇒
      buf(start) & 0xc0 match {
        case 0x00 ⇒
          andThen(unpackFirst(buf(start)))
        case 0x40 ⇒
          context.pushBack(1)
          decode2Bytes
        case 0x80 ⇒
          context.pushBack(1)
          decode3Bytes
        case 0xc0 ⇒
          context.pushBack(1)
          decode4Bytes
      }
    }

    private val decode2Bytes = context.read(2) {
      (buf, start, end) ⇒ andThen(unpack(unpackFirst(buf(start)), buf(start + 1)))
    }

    private val decode3Bytes = context.read(3) {
      (buf, start, end) ⇒ andThen(unpack(unpack(unpackFirst(buf(start)), buf(start + 1)), buf(start + 2)))
    }

    private val decode4Bytes = context.read(4) {
      (buf, start, end) ⇒ andThen(unpack(unpack(unpack(unpackFirst(buf(start)), buf(start + 1)), buf(start + 2)), buf(start + 3)))
    }

    override def toString = "[Variable length encoded Integer field]"
  }

  case class DelimiterEncodedField(delimiter: ByteString, lookAhead: Int = 1024)(val andThen: ByteString ⇒ DecodeStep)(implicit val context: DecodeContext) extends FieldDecoder[ByteString] {
    require(delimiter.size > 0, "Delmiter must be at least one byte long, but it was empty")

    private val delimiterSize = delimiter.size
    private val delimiterFirstByte = delimiter(0)
    private var nextPossibleMatch = -1
    private var frameStart = -1

    @tailrec
    private def scan(buf: ByteString): Int = {
      if (nextPossibleMatch >= buf.size - delimiterSize) -1
      else {
        val possibleMatch = buf.indexOf(delimiterFirstByte, nextPossibleMatch)
        if (possibleMatch >= 0) {
          if (buf.slice(possibleMatch, possibleMatch + delimiterSize) == delimiter) possibleMatch
          else {
            nextPossibleMatch += 1
            scan(buf)
          }
        } else -1
      }
    }

    lazy val decode: DecodeStep =
      context.read(atLeast = 1, atMost = lookAhead) { (buf, start, end) ⇒
        if (nextPossibleMatch == -1) {
          nextPossibleMatch = start
          frameStart = start
        }

        if (end < nextPossibleMatch + delimiterSize)
          decode
        else {
          val delimiterPos = scan(buf)
          if (delimiterPos >= 0) {
            val frameEnd = delimiterPos + delimiterSize
            val currentFrameStart = frameStart
            nextPossibleMatch = -1
            frameStart = -1
            context.pushBack(end - frameEnd)
            andThen(buf.slice(currentFrameStart, frameEnd))
          } else decode
        }
      }

  }
}
