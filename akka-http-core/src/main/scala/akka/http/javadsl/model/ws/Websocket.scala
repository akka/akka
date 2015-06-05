/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

import akka.stream.FlowMaterializer
import akka.stream.javadsl.Flow
import akka.http.javadsl.model._
import akka.http.impl.util.JavaMapping.Implicits._

object Websocket {
  /**
   * If a given request is a Websocket request a response accepting the request is returned using the given handler to
   * handle the Websocket message stream. If the request wasn't a Websocket request a response with status code 400 is
   * returned.
   */
  def handleWebsocketRequestWith(request: HttpRequest, handler: Flow[Message, Message, _]): HttpResponse =
    request.asScala.header[UpgradeToWebsocket] match {
      case Some(header) ⇒ header.handleMessagesWith(handler)
      case None         ⇒ HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected websocket request")
    }
}
