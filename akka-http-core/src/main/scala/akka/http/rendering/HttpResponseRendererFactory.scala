/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import org.reactivestreams.api.Producer
import akka.util.ByteString
import akka.http.model._
import akka.http.util._

class HttpResponseRendererFactory(serverHeader: Option[headers.Server], chunklessStreaming: Boolean) {

  private val serverHeaderPlusDateColonSP: Array[Byte] =
    serverHeader match {
      case None         ⇒ "Date: ".getAsciiBytes
      case Some(header) ⇒ (new ByteArrayRendering(64) ~~ header ~~ Rendering.CrLf ~~ "Date: ").get
    }

  def newRenderer: HttpResponseRenderer = new HttpResponseRenderer

  class HttpResponseRenderer extends (ResponseRenderingContext ⇒ Producer[ByteString]) {
    def apply(ctx: ResponseRenderingContext): Producer[ByteString] = ???
  }
}

case class ResponseRenderingContext(
  response: HttpResponse,
  requestMethod: HttpMethod = HttpMethods.GET,
  requestProtocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
  closeAfterResponseCompletion: Boolean = false)
