/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

import java.lang.{ Iterable ⇒ JIterable }
import akka.http.scaladsl.{ model ⇒ sm }
import akka.http.javadsl.model._
import akka.stream.Materializer
import akka.stream.javadsl.{ Sink, Source, Flow }

/**
 * A virtual header that Websocket requests will contain. Use [[UpgradeToWebsocket.handleMessagesWith]] to
 * create a Websocket handshake response and handle the Websocket message stream with the given handler.
 */
trait UpgradeToWebsocket extends sm.HttpHeader {
  /**
   * Returns the sequence of protocols the client accepts.
   *
   * See http://tools.ietf.org/html/rfc6455#section-1.9
   */
  def getRequestedProtocols(): JIterable[String]

  /**
   * Returns a response that can be used to answer a Websocket handshake request. The connection will afterwards
   * use the given handlerFlow to handle Websocket messages from the client.
   */
  def handleMessagesWith(handlerFlow: Flow[Message, Message, _]): HttpResponse

  /**
   * Returns a response that can be used to answer a Websocket handshake request. The connection will afterwards
   * use the given handlerFlow to handle Websocket messages from the client. The given subprotocol must be one
   * of the ones offered by the client.
   */
  def handleMessagesWith(handlerFlow: Flow[Message, Message, _], subprotocol: String): HttpResponse

  /**
   * Returns a response that can be used to answer a Websocket handshake request. The connection will afterwards
   * use the given inSink to handle Websocket messages from the client and the given outSource to send messages to the client.
   */
  def handleMessagesWith(inSink: Sink[Message, _], outSource: Source[Message, _]): HttpResponse

  /**
   * Returns a response that can be used to answer a Websocket handshake request. The connection will afterwards
   * use the given inSink to handle Websocket messages from the client and the given outSource to send messages to the client.
   *
   * The given subprotocol must be one of the ones offered by the client.
   */
  def handleMessagesWith(inSink: Sink[Message, _], outSource: Source[Message, _], subprotocol: String): HttpResponse
}
