/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import scala.collection.immutable
import akka.http.model.{ HttpRequest, ErrorInfo }
import akka.util.ByteString
import akka.stream2.Operation

private[http] class HttpRequestParser(settings: ParserSettings, rawRequestUriHeader: Boolean = false)(headerParser: HttpHeaderParser = HttpHeaderParser(settings))
  extends Operation.Transformer[ByteString, HttpRequest] {

  def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): HttpRequestParser =
    new HttpRequestParser(settings, rawRequestUriHeader)(headerParser.copyWith(warnOnIllegalHeader))

  def onNext(elem: ByteString): immutable.Seq[HttpRequest] = ???

  override def isComplete: Boolean = ???

  override def onComplete: immutable.Seq[HttpRequest] = ???
}