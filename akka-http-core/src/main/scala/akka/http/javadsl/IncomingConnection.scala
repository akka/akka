/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import akka.NotUsed
import akka.japi.function.Function
import akka.stream.Materializer
import akka.stream.javadsl.Flow
import akka.http.javadsl.model._
import akka.http.scaladsl.{ model ⇒ sm }
import java.util.concurrent.CompletionStage
import scala.concurrent.Future
import scala.compat.java8.FutureConverters._

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
  def flow: Flow[HttpResponse, HttpRequest, NotUsed] = Flow.fromGraph(delegate.flow).asInstanceOf[Flow[HttpResponse, HttpRequest, NotUsed]]

  /**
   * Handles the connection with the given flow, which is materialized exactly once
   * and the respective materialization result returned.
   */
  def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat], materializer: Materializer): Mat =
    delegate.handleWith(handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, Mat]].asScala)(materializer)

  /**
   * Handles the connection with the given handler function.
   */
  def handleWithSyncHandler(handler: Function[HttpRequest, HttpResponse], materializer: Materializer): Unit =
    delegate.handleWithSyncHandler(handler.apply(_).asInstanceOf[sm.HttpResponse])(materializer)

  /**
   * Handles the connection with the given handler function.
   */
  def handleWithAsyncHandler(handler: Function[HttpRequest, CompletionStage[HttpResponse]], materializer: Materializer): Unit =
    delegate.handleWithAsyncHandler(handler.apply(_).toScala.asInstanceOf[Future[sm.HttpResponse]])(materializer)

  /**
   * Handles the connection with the given handler function.
   */
  def handleWithAsyncHandler(handler: Function[HttpRequest, CompletionStage[HttpResponse]], parallelism: Int, materializer: Materializer): Unit =
    delegate.handleWithAsyncHandler(handler.apply(_).toScala.asInstanceOf[Future[sm.HttpResponse]], parallelism)(materializer)
}
