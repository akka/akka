/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import scala.concurrent.Future
import akka.stream.Materializer
import akka.stream.javadsl.Flow
import akka.http.javadsl.model._
import akka.http.scaladsl.{ model ⇒ sm }

/**
 * Represents one accepted incoming HTTP connection.
 */
class IncomingConnection private[http] (delegate: akka.http.scaladsl.Http.IncomingConnection) {
  /**
   * The local address of this connection.
   */
  def localAddress: InetSocketAddress = delegate.localAddress

  /**
   * The address of the remote peer.
   */
  def remoteAddress: InetSocketAddress = delegate.remoteAddress

  /**
   * A flow representing the incoming requests and outgoing responses for this connection.
   *
   * Use `Flow.join` or one of the handleXXX methods to consume handle requests on this connection.
   */
  def flow: Flow[HttpResponse, HttpRequest, Unit] = Flow.adapt(delegate.flow).asInstanceOf[Flow[HttpResponse, HttpRequest, Unit]]

  /**
   * Handles the connection with the given flow, which is materialized exactly once
   * and the respective materialization result returned.
   */
  def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat], materializer: Materializer): Mat =
    delegate.handleWith(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, Mat]].asScala)(materializer)

  /**
   * Handles the connection with the given handler function.
   * Returns the materialization result of the underlying flow materialization.
   */
  def handleWithSyncHandler(handler: Function[HttpRequest, HttpResponse], materializer: Materializer): Unit =
    delegate.handleWithSyncHandler(handler.apply(_).asInstanceOf[sm.HttpResponse])(materializer)

  /**
   * Handles the connection with the given handler function.
   * Returns the materialization result of the underlying flow materialization.
   */
  def handleWithAsyncHandler(handler: Function[HttpRequest, Future[HttpResponse]], materializer: Materializer): Unit =
    delegate.handleWithAsyncHandler(handler.apply(_).asInstanceOf[Future[sm.HttpResponse]])(materializer)
}
