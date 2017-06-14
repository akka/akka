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

      setHandlers(in, out, this)

      override def onPush() = {
        @tailrec
        def parseLines(
          bs:          ByteString,
          from:        Int            = 0,
          at:          Int            = 0,
          parsedLines: Vector[String] = Vector.empty): (ByteString, Vector[String]) =
          if (at >= bs.length)
            (bs.drop(from), parsedLines)
          else
            bs(at) match {
              // Lookahead for LF after CR
              case `cr` if at < bs.length - 1 && bs(at + 1) == lf ⇒
                parseLines(bs, at + 2, at + 2, parsedLines :+ bs.slice(from, at).utf8String)
              // a CR or LF means we found a new slice
              case `cr` | `lf` ⇒
                parseLines(bs, at + 1, at + 1, parsedLines :+ bs.slice(from, at).utf8String)
              // for other input, simply advance
              case _ ⇒
                parseLines(bs, from, at + 1, parsedLines)
            }
        buffer = parseLines(buffer ++ grab(in)) match {
          case (remaining, _) if remaining.size > maxLineSize ⇒
            failStage(new IllegalStateException(s"maxLineSize of $maxLineSize exceeded!"))
            ByteString.empty // Clear buffer
          case (remaining, parsedLines) ⇒
            if (parsedLines.nonEmpty) emitMultiple(out, parsedLines) else pull(in)
            remaining
        }
      }

      override def onPull() = pull(in)
    }
}
