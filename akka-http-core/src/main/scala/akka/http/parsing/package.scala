/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import scala.annotation.tailrec
import akka.util.ByteString
import akka.http.model.{ ErrorInfo, StatusCode, StatusCodes }
import akka.http.util.SingletonException

package object parsing {

  /**
   * INTERNAL API
   */
  private[http] def escape(c: Char): String = c match {
    case '\t'                           ⇒ "\\t"
    case '\r'                           ⇒ "\\r"
    case '\n'                           ⇒ "\\n"
    case x if Character.isISOControl(x) ⇒ "\\u%04x" format c.toInt
    case x                              ⇒ x.toString
  }

  /**
   * INTERNAL API
   */
  private[http] def byteChar(input: ByteString, ix: Int): Char =
    if (ix < input.length) input(ix).toChar else throw NotEnoughDataException

  /**
   * INTERNAL API
   */
  private[http] def asciiString(input: ByteString, start: Int, end: Int): String = {
    @tailrec def build(ix: Int = start, sb: JStringBuilder = new JStringBuilder(end - start)): String =
      if (ix == end) sb.toString else build(ix + 1, sb.append(input(ix).toChar))
    if (start == end) "" else build()
  }
}

package parsing {

  /**
   * INTERNAL API
   */
  private[parsing] class ParsingException(val status: StatusCode,
                                          val info: ErrorInfo) extends RuntimeException(info.formatPretty) {
    def this(status: StatusCode, summary: String = "") =
      this(status, ErrorInfo(if (summary.isEmpty) status.defaultMessage else summary))
    def this(summary: String) =
      this(StatusCodes.BadRequest, ErrorInfo(summary))
  }

  /**
   * INTERNAL API
   */
  private[parsing] object NotEnoughDataException extends SingletonException
}

