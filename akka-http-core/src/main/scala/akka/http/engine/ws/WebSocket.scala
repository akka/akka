/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.ws

import akka.stream.scaladsl.Flow
import akka.util.ByteString

object WebSocket {
  /**
   * Upgrades a Websocket message flow with parsing and rendering to be used on a Websocket connection.
   */
  def websocketHandlerFlow(handler: Flow[Message, Message]): Flow[ByteString, ByteString] =
    websocketFlow(websocketFrameHandler(handler))

  /**
   * Handles control frames automatically and passes messages through the handler.
   */
  def websocketFrameHandler(handler: Flow[Message, Message]): Flow[Frame, Frame] = ???

  /**
   * Handles control frames.
   */
  def controlFrameHandler: Flow[ControlFrame, Frame] = ???
  def websocketFlow(frameHandler: Flow[Frame, Frame]): Flow[ByteString, ByteString] =
    Flow[ByteString]
      .via(websocketParser)
      .via(frameHandler)
      .via(websocketRenderer)

  def websocketParser: Flow[ByteString, Frame] = ???
  def websocketRenderer: Flow[Frame, ByteString] = ???
}
