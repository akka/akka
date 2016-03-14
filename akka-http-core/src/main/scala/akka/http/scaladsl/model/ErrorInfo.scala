/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import StatusCodes.ClientError

/**
 * Two-level model of error information.
 * The summary should explain what is wrong with the request or response *without* directly
 * repeating anything present in the message itself (in order to not open holes for XSS attacks),
 * while the detail can contain additional information from any source (even the request itself).
 */
final case class ErrorInfo(summary: String = "", detail: String = "") {
  def withSummary(newSummary: String) = copy(summary = newSummary)
  def withSummaryPrepended(prefix: String) = withSummary(if (summary.isEmpty) prefix else prefix + ": " + summary)
  def withFallbackSummary(fallbackSummary: String) = if (summary.isEmpty) withSummary(fallbackSummary) else this
  def formatPretty = if (summary.isEmpty) detail else if (detail.isEmpty) summary else summary + ": " + detail
  def format(withDetail: Boolean): String = if (withDetail) formatPretty else summary
}

object ErrorInfo {
  /**
   * Allows constructing an `ErrorInfo` from a single string.
   * Used for example when catching exceptions generated by the header value parser, which doesn't provide
   * summary/details information but structures its exception messages accordingly.
   */
  def fromCompoundString(message: String): ErrorInfo = message.split(": ", 2) match {
    case Array(summary, detail) ⇒ apply(summary, detail)
    case _                      ⇒ ErrorInfo("", message)
  }
}

/** Marker for exceptions that provide an ErrorInfo */
abstract class ExceptionWithErrorInfo(info: ErrorInfo) extends RuntimeException(info.formatPretty)

case class IllegalUriException(info: ErrorInfo) extends ExceptionWithErrorInfo(info)
object IllegalUriException {
  def apply(summary: String, detail: String = ""): IllegalUriException = apply(ErrorInfo(summary, detail))
}

case class IllegalHeaderException(info: ErrorInfo) extends ExceptionWithErrorInfo(info)
object IllegalHeaderException {
  def apply(summary: String, detail: String = ""): IllegalHeaderException = apply(ErrorInfo(summary, detail))
}

case class InvalidContentLengthException(info: ErrorInfo) extends ExceptionWithErrorInfo(info)
object InvalidContentLengthException {
  def apply(summary: String, detail: String = ""): InvalidContentLengthException = apply(ErrorInfo(summary, detail))
}

case class ParsingException(info: ErrorInfo) extends ExceptionWithErrorInfo(info)
object ParsingException {
  def apply(summary: String, detail: String = ""): ParsingException = apply(ErrorInfo(summary, detail))
}

case class IllegalRequestException(info: ErrorInfo, status: ClientError) extends ExceptionWithErrorInfo(info)
object IllegalRequestException {
  def apply(status: ClientError): IllegalRequestException = apply(ErrorInfo(status.defaultMessage), status)
  def apply(status: ClientError, info: ErrorInfo): IllegalRequestException = apply(info.withFallbackSummary(status.defaultMessage), status)
  def apply(status: ClientError, detail: String): IllegalRequestException = apply(ErrorInfo(status.defaultMessage, detail), status)
}

case class IllegalResponseException(info: ErrorInfo) extends ExceptionWithErrorInfo(info)
object IllegalResponseException {
  def apply(summary: String, detail: String = ""): IllegalResponseException = apply(ErrorInfo(summary, detail))
}

case class EntityStreamException(info: ErrorInfo) extends ExceptionWithErrorInfo(info)
object EntityStreamException {
  def apply(summary: String, detail: String = ""): EntityStreamException = apply(ErrorInfo(summary, detail))
}

/**
 * This exception is thrown when the size of the HTTP Entity exceeds the configured limit.
 * It is possible to configure the limit using configuration options `akka.http.parsing.max-content-length`
 * or specifically for the server or client side by setting `akka.http.[server|client].parsing.max-content-length`.
 *
 * The limit can also be configured in code, by calling [[HttpEntity#withSizeLimit]]
 * on the entity before materializing its `dataBytes` stream.
 */
final case class EntityStreamSizeException(limit: Long, actualSize: Option[Long] = None) extends RuntimeException {

  override def getMessage = toString

  override def toString =
    s"EntityStreamSizeException: actual entity size ($actualSize) exceeded content length limit ($limit bytes)! " +
      s"You can configure this by setting `akka.http.[server|client].parsing.max-content-length` or calling `HttpEntity.withSizeLimit` " +
      s"before materializing the dataBytes stream."
}

case class RequestTimeoutException(request: HttpRequest, message: String) extends RuntimeException(message)
