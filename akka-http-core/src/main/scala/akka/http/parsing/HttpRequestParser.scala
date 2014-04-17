/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import scala.collection.immutable
import akka.http.model.{ HttpResponse, HttpRequest, ErrorInfo }
import akka.util.ByteString
import akka.stream2.Operation

private[http] class HttpRequestParser(settings: ParserSettings, rawRequestUriHeader: Boolean = false)(headerParser: HttpHeaderParser = HttpHeaderParser(settings))
  extends Operation.Transformer[ByteString, Either[HttpResponse, HttpRequest]] {

  def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): HttpRequestParser =
    new HttpRequestParser(settings, rawRequestUriHeader)(headerParser.copyWith(warnOnIllegalHeader))

  def onNext(elem: ByteString): immutable.Seq[Either[HttpResponse, HttpRequest]] = ???

  override def isComplete: Boolean = ???

  override def onComplete: immutable.Seq[Either[HttpResponse, HttpRequest]] = ???
}