/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.http.model.StatusCodes.{ ServerError, ClientError }

/**
 * Two-level model of error information.
 * The summary should explain what is wrong with the request or response *without* directly
 * repeating anything present in the message itself (in order to not open holes for XSS attacks),
 * while the detail can contain additional information from any source (even the request itself).
 */
case class ErrorInfo(summary: String = "", detail: String = "") {
  def withSummary(newSummary: String) = copy(summary = newSummary)
  def withSummaryPrepended(prefix: String) = withSummary(if (summary.isEmpty) prefix else prefix + ": " + summary)
  def withFallbackSummary(fallbackSummary: String) = if (summary.isEmpty) withSummary(fallbackSummary) else this
  def formatPretty = if (summary.isEmpty) detail else if (detail.isEmpty) summary else summary + ": " + detail
  def format(withDetail: Boolean): String = if (withDetail) formatPretty else summary
}

object ErrorInfo {
  def fromCompoundString(message: String): ErrorInfo = message.split(": ", 2) match {
    case Array(summary, detail) ⇒ apply(summary, detail)
    case _                      ⇒ ErrorInfo("", message)
  }
}

/** Marker for exceptions that provide an ErrorInfo */
abstract case class ExceptionWithErrorInfo(info: ErrorInfo) extends RuntimeException(info.formatPretty)

class IllegalUriException(info: ErrorInfo) extends ExceptionWithErrorInfo(info) {
  def this(summary: String, detail: String = "") = this(ErrorInfo(summary, detail))
}

// the following exceptions are not used directly in spray-http
// but by the spray-routing and spray-servlet modules
// since the Client- and ServerError types are defined in spray-http the only
// commonly accessible place for this definition is here in spray-http

class IllegalRequestException private (info: ErrorInfo, val status: ClientError)
  extends ExceptionWithErrorInfo(info) {
  def this(status: ClientError) = this(ErrorInfo(status.defaultMessage), status)
  def this(status: ClientError, info: ErrorInfo) = this(info.withFallbackSummary(status.defaultMessage), status)
  def this(status: ClientError, detail: String) = this(ErrorInfo(status.defaultMessage, detail), status)
}

class RequestProcessingException private (info: ErrorInfo, val status: ServerError)
  extends ExceptionWithErrorInfo(info) {
  def this(status: ServerError) = this(ErrorInfo(status.defaultMessage), status)
  def this(status: ServerError, info: ErrorInfo) = this(info.withFallbackSummary(status.defaultMessage), status)
  def this(status: ServerError, detail: String) = this(ErrorInfo(status.defaultMessage, detail), status)
}

class ParsingException(msg: String) extends RuntimeException(msg)