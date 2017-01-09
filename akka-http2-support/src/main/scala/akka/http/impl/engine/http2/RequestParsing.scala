/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.scaladsl.Source

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

/**
 * INTERNAL API
 */
private[http2] object RequestParsing {
  def parseRequest(subStream: Http2SubStream): HttpRequest = {
    @tailrec
    def rec(
      remainingHeaders: Seq[(String, String)],
      method:           HttpMethod                = null,
      scheme:           String                    = null,
      authority:        Uri.Authority             = Uri.Authority.Empty,
      path:             String                    = null,
      contentType:      ContentType               = ContentTypes.`application/octet-stream`,
      contentLength:    Long                      = -1,
      headers:          VectorBuilder[HttpHeader] = new VectorBuilder[HttpHeader]
    ): HttpRequest =
      if (remainingHeaders.isEmpty) {
        // 8.1.2.3: these pseudo header fields are mandatory for a request
        checkRequiredField(":scheme", scheme)
        checkRequiredField(":method", method)
        checkRequiredField(":path", path)

        headers += Http2StreamIdHeader(subStream.streamId)

        val entity =
          if (subStream.data == Source.empty || contentLength == 0) HttpEntity.Empty
          else if (contentLength > 0) HttpEntity.Default(contentType, contentLength, subStream.data)
          else HttpEntity.Chunked.fromData(contentType, subStream.data)

        val uri = Uri(scheme, authority).withPath(Uri.Path(path))
        HttpRequest(
          method, uri, headers.result(), entity, HttpProtocols.`HTTP/2.0`
        )
      } else remainingHeaders.head match {
        case (":scheme", value) ⇒
          rec(remainingHeaders.tail, method, value, authority, path, contentType, contentLength, headers)
        case (":method", value) ⇒
          val m = HttpMethods.getForKey(value).getOrElse(malformedRequest(s"Unknown HTTP method: '$value'"))
          rec(remainingHeaders.tail, m, scheme, authority, path, contentType, contentLength, headers)
        case (":path", path) ⇒
          rec(remainingHeaders.tail, method, scheme, authority, path, contentType, contentLength, headers)
        case (":authority", value) ⇒
          val authority = Uri.Authority.parse(value)
          rec(remainingHeaders.tail, method, scheme, authority, path, contentType, contentLength, headers)

        case ("content-type", ct) ⇒
          val contentType = ContentType.parse(ct).right.getOrElse(malformedRequest(s"Invalid content-type: '$ct'"))
          rec(remainingHeaders.tail, method, scheme, authority, path, contentType, contentLength, headers)

        case ("content-length", length) ⇒
          val contentLength = length.toLong
          rec(remainingHeaders.tail, method, scheme, authority, path, contentType, contentLength, headers)

        case (name, value) ⇒
          // FIXME: parse to real headers
          rec(remainingHeaders.tail, method, scheme, authority, path, contentType, contentLength, headers += RawHeader(name, value))
      }

    rec(subStream.initialHeaders.keyValuePairs)
  }

  def checkRequiredField(name: String, value: AnyRef): Unit =
    if (value eq null) malformedRequest(s"Mandatory pseudo-header field $name missing")
  def malformedRequest(msg: String): Nothing =
    throw new RuntimeException(s"Malformed request: $msg")
}
