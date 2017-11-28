/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http
package scaladsl
package unmarshalling
package sse

import akka.annotation.InternalApi
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString
import scala.annotation.tailrec

/** INTERNAL API */
@InternalApi
private object LineParser {

  private val cr = '\r'.toByte

  private val lf = '\n'.toByte
}

/** INTERNAL API */
@InternalApi
private final class LineParser(maxLineSize: Int) extends GraphStage[FlowShape[ByteString, String]] {

  override val shape = FlowShape(Inlet[ByteString]("LineParser.in"), Outlet[String]("LineParser.out"))

  override def createLogic(attributes: Attributes) =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      import LineParser._
      import shape._

      private var buffer = ByteString.empty
      private var lastCharWasCr = false

      setHandlers(in, out, this)

      override def onPush() = {
        @tailrec
        def parseLines(
          bs:          ByteString,
          from:        Int            = 0,
          at:          Int            = 0,
          parsedLines: Vector[String] = Vector.empty,
          slicedCr:    Boolean        = false): (ByteString, Vector[String], Boolean) =
          if (at >= bs.length)
            (bs.drop(from), parsedLines, slicedCr)
          else
            bs(at) match {
              // Lookahead for LF after CR
              case `cr` if at < bs.length - 1 && bs(at + 1) == lf ⇒
                parseLines(bs, at + 2, at + 2, parsedLines :+ bs.slice(from, at).utf8String)
              // if is a CR but we don't know the next character, slice it but flag that the last character was a CR so if the next happens to be a LF we just ignore
              case `cr` ⇒
                parseLines(bs, at + 1, at + 1, parsedLines :+ bs.slice(from, at).utf8String, slicedCr = true)
              // if is a LF and we just sliced a CR then we simply advance
              case `lf` if slicedCr ⇒
                parseLines(bs, at + 1, at + 1, parsedLines)
              // a CR or LF means we found a new slice
              case `lf` ⇒
                parseLines(bs, at + 1, at + 1, parsedLines :+ bs.slice(from, at).utf8String)
              // for other input, simply advance
              case _ ⇒
                parseLines(bs, from, at + 1, parsedLines)
            }

        // start the search where it ended, prevent iterating over all the buffer again
        val currentBufferStart = math.max(0, buffer.length - 1)
        buffer = parseLines(buffer ++ grab(in), at = currentBufferStart, slicedCr = lastCharWasCr) match {
          case (remaining, _, _) if remaining.size > maxLineSize ⇒
            failStage(new IllegalStateException(s"maxLineSize of $maxLineSize exceeded!"))
            ByteString.empty // Clear buffer
          case (remaining, parsedLines, _lastCharWasCr) ⇒
            if (parsedLines.nonEmpty) emitMultiple(out, parsedLines) else pull(in)
            lastCharWasCr = _lastCharWasCr
            remaining
        }
      }

      override def onPull() = pull(in)
    }
}
