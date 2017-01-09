/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.scaladsl.model.{ ContentType, ContentTypes, HttpHeader, HttpResponse }
import akka.http.scaladsl.model.http2.Http2StreamIdHeader

import scala.collection.immutable.VectorBuilder

private[http2] object ResponseRendering {
  def renderResponse(response: HttpResponse): Http2SubStream = {
    def failBecauseOfMissingHeader: Nothing =
      // header is missing, shutting down because we will most likely otherwise miss a response and leak a substream
      // TODO: optionally a less drastic measure would be only resetting all the active substreams
      throw new RuntimeException("Received response for HTTP/2 request without Http2StreamIdHeader. Failing connection.")

    val streamId = response.header[Http2StreamIdHeader].getOrElse(failBecauseOfMissingHeader).streamId
    val headerPairs = new VectorBuilder[(String, String)]()

    // From https://tools.ietf.org/html/rfc7540#section-8.1.2.4:
    //   HTTP/2 does not define a way to carry the version or reason phrase
    //   that is included in an HTTP/1.1 status line.
    headerPairs += ":status" → response.status.intValue.toString

    if (response.entity.contentType != ContentTypes.NoContentType)
      headerPairs += "content-type" → response.entity.contentType.toString

    response.entity.contentLengthOption.foreach(headerPairs += "content-length" → _.toString)

    headerPairs ++=
      response.headers.collect {
        case header: HttpHeader if header.renderInResponses ⇒ header.lowercaseName → header.value
      }

    val headers = ParsedHeadersFrame(streamId, endStream = response.entity.isKnownEmpty, headerPairs.result())

    Http2SubStream(
      headers,
      response.entity.dataBytes
    )
  }
}
