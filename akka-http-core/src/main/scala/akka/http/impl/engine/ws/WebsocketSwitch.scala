/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

/** Internal interface between the handshake and the stream setup to evoke the switch to the websocket protocol */
private[http] trait WebsocketSwitch {
  def switchToWebsocket(handlerFlow: Flow[FrameEvent, FrameEvent, Any])(implicit mat: FlowMaterializer): Unit
}
