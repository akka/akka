/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

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

class ParsingException(info: ErrorInfo) extends ExceptionWithErrorInfo(info) {
  def this(summary: String, detail: String = "") = this(ErrorInfo(summary, detail))
}