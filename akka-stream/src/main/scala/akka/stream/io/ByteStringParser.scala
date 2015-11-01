/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import scala.util.control.NoStackTrace
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import scala.annotation.tailrec

abstract class ByteStringParser[T] extends GraphStage[FlowShape[ByteString, T]] {
  import ByteStringParser._

  private val bytesIn = Inlet[ByteString]("bytesIn")
  private val objOut = Outlet[T]("objOut")

  final override val shape = FlowShape(bytesIn, objOut)

  class ParsingLogic extends GraphStageLogic(shape) {
    override def preStart(): Unit = pull(bytesIn)
    setHandler(objOut, eagerTerminateOutput)

    private var buffer = ByteString.empty
    private var current: ParseStep[T] = FinishedParser

    final protected def startWith(step: ParseStep[T]): Unit = current = step

    @tailrec private def doParse(): Unit =
      if (buffer.nonEmpty) {
        val cont = try {
          val reader = new ByteReader(buffer)
          val (elem, next) = current.parse(reader)
          emit(objOut, elem)
          if (next == FinishedParser) {
            completeStage()
            false
          } else {
            buffer = reader.remainingData
            current = next
            true
          }
        } catch {
          case NeedMoreData ⇒
            pull(bytesIn)
            false
        }
        if (cont) doParse()
      } else pull(bytesIn)

    setHandler(bytesIn, new InHandler {
      override def onPush(): Unit = {
        buffer ++= grab(bytesIn)
        doParse()
      }
      override def onUpstreamFinish(): Unit =
        if (buffer.isEmpty) completeStage()
        else current.onTruncation()
    })
  }
}

object ByteStringParser {

  trait ParseStep[+T] {
    def parse(reader: ByteReader): (T, ParseStep[T])
    def onTruncation(): Unit = throw new IllegalStateException("truncated data in ByteStringParser")
  }

  object FinishedParser extends ParseStep[Nothing] {
    def parse(reader: ByteReader) =
      throw new IllegalStateException("no initial parser installed: you must use startWith(...)")
  }

  val NeedMoreData = new Exception with NoStackTrace

  class ByteReader(input: ByteString) {

    private[this] var off = 0

    def hasRemaining: Boolean = off < input.size
    def remainingSize: Int = input.size - off

    def currentOffset: Int = off
    def remainingData: ByteString = input.drop(off)
    def fromStartToHere: ByteString = input.take(off)

    def take(n: Int): ByteString =
      if (off + n <= input.length) {
        val o = off
        off = o + n
        input.slice(o, off)
      } else throw NeedMoreData
    def takeAll(): ByteString = {
      val ret = remainingData
      off = input.size
      ret
    }

    def readByte(): Int =
      if (off < input.length) {
        val x = input(off)
        off += 1
        x & 0xFF
      } else throw NeedMoreData
    def readShortLE(): Int = readByte() | (readByte() << 8)
    def readIntLE(): Int = readShortLE() | (readShortLE() << 16)
    def readLongLE(): Long = (readIntLE() & 0xffffffffL) | ((readIntLE() & 0xffffffffL) << 32)

    def readShortBE(): Int = (readByte() << 8) | readByte()
    def readIntBE(): Int = (readShortBE() << 16) | readShortBE()
    def readLongBE(): Long = ((readIntBE() & 0xffffffffL) << 32) | (readIntBE() & 0xffffffffL)

    def skip(numBytes: Int): Unit =
      if (off + numBytes <= input.length) off += numBytes
      else throw NeedMoreData
    def skipZeroTerminatedString(): Unit = while (readByte() != 0) {}
  }
}