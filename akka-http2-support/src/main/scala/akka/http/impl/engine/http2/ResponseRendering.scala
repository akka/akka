/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.event.LoggingAdapter
import akka.http.impl.util.StringRendering
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Date
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.http.scaladsl.settings.ServerSettings

import scala.collection.immutable
import scala.collection.immutable.VectorBuilder

private[http2] object ResponseRendering {

  @volatile
  private var cachedDateHeader = (0L, ("", ""))

  private def dateHeader(): (String, String) = {
    val cachedSeconds = cachedDateHeader._1
    val now = System.currentTimeMillis()
    if (now / 1000 > cachedSeconds) {
      val r = new StringRendering
      DateTime(now).renderRfc1123DateTimeString(r)
      cachedDateHeader = (now, Date.lowercaseName → r.get)
    }
    cachedDateHeader._2
  }

  def renderResponse(settings: ServerSettings, log: LoggingAdapter): HttpResponse ⇒ Http2SubStream = {
    def failBecauseOfMissingHeader: Nothing =
      // header is missing, shutting down because we will most likely otherwise miss a response and leak a substream
      // TODO: optionally a less drastic measure would be only resetting all the active substreams
      throw new RuntimeException("Received response for HTTP/2 request without Http2StreamIdHeader. Failing connection.")

    val serverHeader = settings.serverHeader.map(h ⇒ h.lowercaseName → h.value)

    { (response: HttpResponse) ⇒
      val streamId = response.header[Http2StreamIdHeader].getOrElse(failBecauseOfMissingHeader).streamId
      val headerPairs = new VectorBuilder[(String, String)]()

      // From https://tools.ietf.org/html/rfc7540#section-8.1.2.4:
      //   HTTP/2 does not define a way to carry the version or reason phrase
      //   that is included in an HTTP/1.1 status line.
      headerPairs += ":status" → response.status.intValue.toString

      if (response.entity.contentType != ContentTypes.NoContentType)
        headerPairs += "content-type" → response.entity.contentType.toString

      response.entity.contentLengthOption.foreach(headerPairs += "content-length" → _.toString)

      renderHeaders(response.headers, headerPairs, serverHeader, log)

      val headers = ParsedHeadersFrame(streamId, endStream = response.entity.isKnownEmpty, headerPairs.result(), None)

      Http2SubStream(
        headers,
        response.entity.dataBytes
      )
    }
  }

  private[http2] def renderHeaders(
    headersSeq:   immutable.Seq[HttpHeader],
    headerPairs:  VectorBuilder[(String, String)],
    serverHeader: Option[(String, String)],
    log:          LoggingAdapter
  ): Unit = {
    def suppressionWarning(h: HttpHeader, msg: String): Unit =
      log.warning("Explicitly set HTTP header '{}' is ignored, {}", h, msg)

    // optimized, as it is done for every response
    val headers = headersSeq.toArray
    var serverSeen, dateSeen = false
    var idx = 0
    def addHeader(h: HttpHeader): Unit = {
      headerPairs += h.lowercaseName → h.value
    }

    while (idx < headers.length) {
      import akka.http.scaladsl.model.headers._
      val header = headers(idx)
      if (header.renderInResponses) {
        header match {
          case x: Server ⇒
            addHeader(x)
            serverSeen = true

          case x: Date ⇒
            addHeader(x)
            dateSeen = true

          case x: CustomHeader ⇒
            addHeader(x)

          case x: RawHeader if (x is "content-type") || (x is "content-length") || (x is "transfer-encoding") ||
            (x is "date") || (x is "server") || (x is "connection") ⇒
            suppressionWarning(x, "illegal RawHeader")

          case x: `Content-Length` ⇒
            suppressionWarning(x, "explicit `Content-Length` header is not allowed. Use the appropriate HttpEntity subtype.")

          case x: `Content-Type` ⇒
            suppressionWarning(x, "explicit `Content-Type` header is not allowed. Set `HttpResponse.entity.contentType` instead.")

          case x: `Transfer-Encoding` ⇒
            suppressionWarning(x, "`Transfer-Encoding` header is not allowed for HTTP/2")

          case x: Connection ⇒
            suppressionWarning(x, "`Connection` header is not allowed for HTTP/2")

          case x ⇒
            addHeader(x)
        }
      }
      idx += 1
    }

    if (!dateSeen) {
      headerPairs += dateHeader()
    }

    if (!serverSeen) {
      serverHeader match {
        case Some(serverTuple) ⇒ headerPairs += serverTuple
        case None              ⇒
      }
    }

  }

}
