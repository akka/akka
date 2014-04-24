/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import scala.collection.immutable
import akka.http.model.{ HttpResponse, HttpRequest, ErrorInfo }
import akka.util.ByteString
import akka.stream2.Operation

private[http] class HttpRequestParser(settings: ParserSettings, rawRequestUriHeader: Boolean = false)(headerParser: HttpHeaderParser = HttpHeaderParser(settings))
  extends Operation.Transformer[ByteString, HttpRequestParser.ParserOutput] {

  def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): HttpRequestParser =
    new HttpRequestParser(settings, rawRequestUriHeader)(headerParser.copyWith(warnOnIllegalHeader))

  def onNext(elem: ByteString): immutable.Seq[HttpRequestParser.ParserOutput] = ???

  override def isComplete: Boolean = ???

  override def onComplete: immutable.Seq[HttpRequestParser.ParserOutput] = ???
}

private[http] object HttpRequestParser {
  sealed trait ParserOutput
  case class RequestStart() extends ParserOutput
  case class EntityPart() extends ParserOutput
  case class EntityChunk() extends ParserOutput
  case class ParseError(errorResponse: HttpResponse) extends ParserOutput
}