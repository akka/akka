/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.ws

import java.lang.Iterable

import akka.http.javadsl
import akka.stream

import scala.collection.immutable
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

import akka.http.model.HttpResponse

/**
 * A custom header that will be added to an Websocket upgrade HttpRequest that
 * enables a request handler to upgrade this connection to a Websocket connection and
 * registers a Websocket handler.
 */
trait UpgradeToWebsocket extends javadsl.model.ws.UpgradeToWebsocket {
  /**
   * A sequence of protocols the client accepts.
   *
   * See http://tools.ietf.org/html/rfc6455#section-1.9
   */
  def requestedProtocols: immutable.Seq[String]

  /**
   * The high-level interface to create a Websocket server based on "messages".
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to Websocket and
   * use the supplied handler to handle incoming Websocket messages. Optionally,
   * a subprotocol out of the ones requested by the client can be chosen.
   */
  def handleMessages(handlerFlow: Flow[Message, Message, Any], subprotocol: Option[String] = None)(implicit mat: FlowMaterializer): HttpResponse

  import scala.collection.JavaConverters._
  def getRequestedProtocols(): Iterable[String] = requestedProtocols.asJava
  def handleMessagesWith(handlerFlow: stream.javadsl.Flow[javadsl.model.ws.Message, javadsl.model.ws.Message, _], materializer: FlowMaterializer): HttpResponse =
    handleMessages(adaptJavaFlow(handlerFlow))(materializer)

  def handleMessagesWith(handlerFlow: stream.javadsl.Flow[javadsl.model.ws.Message, javadsl.model.ws.Message, _], subprotocol: String, materializer: FlowMaterializer): HttpResponse =
    handleMessages(adaptJavaFlow(handlerFlow), subprotocol = Some(subprotocol))(materializer)

  private[this] def adaptJavaFlow(handlerFlow: stream.javadsl.Flow[javadsl.model.ws.Message, javadsl.model.ws.Message, _]): Flow[Message, Message, Any] =
    Flow[Message].map(javadsl.model.ws.Message.adapt).via(handlerFlow.asScala).map(_.asScala)
}
