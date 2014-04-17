/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import org.reactivestreams.api.Producer
import akka.util.ByteString
import akka.http.model.HttpResponse
import akka.http.model.headers.Server
import akka.http.util._

class HttpResponseRendererFactory(serverHeader: Option[Server], chunklessStreaming: Boolean) {

  private val serverHeaderPlusDateColonSP: Array[Byte] =
    serverHeader match {
      case None         ⇒ "Date: ".getAsciiBytes
      case Some(header) ⇒ (new ByteArrayRendering(64) ~~ header ~~ Rendering.CrLf ~~ "Date: ").get
    }

  def newRenderer: HttpResponseRenderer = new HttpResponseRenderer

  class HttpResponseRenderer extends (HttpResponse ⇒ Producer[ByteString]) {
    def apply(response: HttpResponse): Producer[ByteString] = ???
  }
}
