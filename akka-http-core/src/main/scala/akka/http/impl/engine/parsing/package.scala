/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import akka.http.scaladsl.settings.ParserSettings

import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.http.scaladsl.model.{ ErrorInfo, StatusCode, StatusCodes }
import akka.http.impl.util.SingletonException

/**
 * INTERNAL API
 */
package object parsing {

  private[http] def escape(c: Char): String = c match {
    case '\t'                           ⇒ "\\t"
    case '\r'                           ⇒ "\\r"
    case '\n'                           ⇒ "\\n"
    case x if Character.isISOControl(x) ⇒ "\\u%04x" format c.toInt
    case x                              ⇒ x.toString
  }

  private[http] def byteChar(input: ByteString, ix: Int): Char = byteAt(input, ix).toChar

  private[http] def byteAt(input: ByteString, ix: Int): Byte =
    if (ix < input.length) input(ix) else throw NotEnoughDataException

  private[http] def asciiString(input: ByteString, start: Int, end: Int): String = {
    @tailrec def build(ix: Int = start, sb: JStringBuilder = new JStringBuilder(end - start)): String =
      if (ix == end) sb.toString else build(ix + 1, sb.append(input(ix).toChar))
    if (start == end) "" else build()
  }

  private[http] def logParsingError(info: ErrorInfo, log: LoggingAdapter,
                                    setting: ParserSettings.ErrorLoggingVerbosity): Unit =
    setting match {
      case ParserSettings.ErrorLoggingVerbosity.Off    ⇒ // nothing to do
      case ParserSettings.ErrorLoggingVerbosity.Simple ⇒ log.warning(info.summary)
      case ParserSettings.ErrorLoggingVerbosity.Full   ⇒ log.warning(info.formatPretty)
    }
}

package parsing {

  /**
   * INTERNAL API
   */
  private[parsing] class ParsingException(
    val status: StatusCode,
    val info:   ErrorInfo) extends RuntimeException(info.formatPretty) {
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

