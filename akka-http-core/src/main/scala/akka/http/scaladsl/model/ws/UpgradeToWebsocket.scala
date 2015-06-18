/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.ws

import java.lang.Iterable
import akka.http.impl.util.JavaMapping

import scala.collection.immutable
import akka.stream
import akka.stream.javadsl
import akka.stream.scaladsl.{ Sink, Source, Flow }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.model.HttpResponse

/**
 * A custom header that will be added to an Websocket upgrade HttpRequest that
 * enables a request handler to upgrade this connection to a Websocket connection and
 * registers a Websocket handler.
 */
trait UpgradeToWebsocket extends jm.ws.UpgradeToWebsocket {
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
   * use the supplied handler to handle incoming Websocket messages.
   *
   * Optionally, a subprotocol out of the ones requested by the client can be chosen.
   */
  def handleMessages(handlerFlow: Flow[Message, Message, Any],
                     subprotocol: Option[String] = None): HttpResponse

  /**
   * The high-level interface to create a Websocket server based on "messages".
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to Websocket and
   * use the supplied inSink to consume messages received from the client and
   * the supplied outSource to produce message to sent to the client.
   *
   * Optionally, a subprotocol out of the ones requested by the client can be chosen.
   */
  def handleMessagesWithSinkSource(inSink: Sink[Message, Any],
                                   outSource: Source[Message, Any],
                                   subprotocol: Option[String] = None): HttpResponse =
    handleMessages(Flow.wrap(inSink, outSource)((_, _) ⇒ ()), subprotocol)

  import scala.collection.JavaConverters._

  /**
   * Java API
   */
  def getRequestedProtocols(): Iterable[String] = requestedProtocols.asJava

  /**
   * Java API
   */
  def handleMessagesWith(handlerFlow: stream.javadsl.Flow[jm.ws.Message, jm.ws.Message, _]): HttpResponse =
    handleMessages(JavaMapping.toScala(handlerFlow))

  /**
   * Java API
   */
  def handleMessagesWith(handlerFlow: stream.javadsl.Flow[jm.ws.Message, jm.ws.Message, _], subprotocol: String): HttpResponse =
    handleMessages(JavaMapping.toScala(handlerFlow), subprotocol = Some(subprotocol))

  /**
   * Java API
   */
  def handleMessagesWith(inSink: stream.javadsl.Sink[jm.ws.Message, _], outSource: javadsl.Source[jm.ws.Message, _]): HttpResponse =
    handleMessages(createScalaFlow(inSink, outSource))

  /**
   * Java API
   */
  def handleMessagesWith(inSink: stream.javadsl.Sink[jm.ws.Message, _],
                         outSource: javadsl.Source[jm.ws.Message, _],
                         subprotocol: String): HttpResponse =
    handleMessages(createScalaFlow(inSink, outSource), subprotocol = Some(subprotocol))

  private[this] def createScalaFlow(inSink: stream.javadsl.Sink[jm.ws.Message, _], outSource: stream.javadsl.Source[jm.ws.Message, _]): Flow[Message, Message, Any] =
    JavaMapping.toScala(Flow.wrap(inSink.asScala, outSource.asScala)((_, _) ⇒ ()).asJava)
}
