/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import akka.stream.javadsl.Flow
import akka.http.javadsl.model._
import akka.http.impl.util.JavaMapping.Implicits._

object WebSocket {
  /**
   * If a given request is a WebSocket request a response accepting the request is returned using the given handler to
   * handle the WebSocket message stream. If the request wasn't a WebSocket request a response with status code 400 is
   * returned.
   */
  def handleWebSocketRequestWith(request: HttpRequest, handler: Flow[Message, Message, _]): HttpResponse =
    request.asScala.header[UpgradeToWebSocket] match {
      case Some(header) ⇒ header.handleMessagesWith(handler)
      case None         ⇒ HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected WebSocket request")
    }
}
