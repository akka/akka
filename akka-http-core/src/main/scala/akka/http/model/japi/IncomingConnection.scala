/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi

import java.net.InetSocketAddress

import akka.japi.function.Function

import scala.concurrent.Future

import akka.http
import http.model
import akka.stream.FlowMaterializer
import akka.stream.javadsl.Flow

/**
 * Represents one accepted incoming HTTP connection.
 */
class IncomingConnection private[http] (delegate: http.Http.IncomingConnection) {
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
  def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat], materializer: FlowMaterializer): Mat =
    delegate.handleWith(handler.asInstanceOf[Flow[model.HttpRequest, model.HttpResponse, Mat]].asScala)(materializer)

  /**
   * Handles the connection with the given handler function.
   * Returns the materialization result of the underlying flow materialization.
   */
  def handleWithSyncHandler(handler: Function[HttpRequest, HttpResponse], materializer: FlowMaterializer): Unit =
    delegate.handleWithSyncHandler(handler.apply(_).asInstanceOf[model.HttpResponse])(materializer)

  /**
   * Handles the connection with the given handler function.
   * Returns the materialization result of the underlying flow materialization.
   */
  def handleWithAsyncHandler(handler: Function[HttpRequest, Future[HttpResponse]], materializer: FlowMaterializer): Unit =
    delegate.handleWithAsyncHandler(handler.apply(_).asInstanceOf[Future[model.HttpResponse]])(materializer)
}
