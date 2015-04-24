/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

import akka.stream.FlowMaterializer
import akka.stream.javadsl.Flow

import akka.http.model.japi.JavaMapping.Implicits._

import akka.http.model.japi.{ StatusCodes, HttpResponse, HttpRequest }

object Websocket {
  /**
   * If a given request is a Websocket request a response accepting the request is returned using the given handler to
   * handle the Websocket message stream. If the request wasn't a Websocket request a response with status code 400 is
   * returned.
   */
  def handleWebsocketRequestWith(request: HttpRequest, handler: Flow[Message, Message, _], materializer: FlowMaterializer): HttpResponse =
    request.asScala.header[UpgradeToWebsocket] match {
      case Some(header) ⇒ header.handleMessagesWith(handler, materializer)
      case None         ⇒ HttpResponse.create().withStatus(StatusCodes.BAD_REQUEST).withEntity("Expected websocket request")
    }
}
