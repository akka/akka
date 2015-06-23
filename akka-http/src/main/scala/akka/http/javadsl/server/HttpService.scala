/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.http.scaladsl.{ server, Http }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.impl.server.RouteImplementation
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.{ Keep, Sink }

trait HttpServiceBase {
  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem): Future[ServerBinding] = {
    implicit val sys = system
    implicit val mat = ActorMaterializer()
    handleConnectionsWithRoute(interface, port, route, system, mat)
  }

  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem, materializer: Materializer): Future[ServerBinding] =
    handleConnectionsWithRoute(interface, port, route, system, materializer)

  /**
   * Uses the route to handle incoming connections and requests for the ServerBinding.
   */
  def handleConnectionsWithRoute(interface: String, port: Int, route: Route, system: ActorSystem, materializer: Materializer): Future[ServerBinding] = {
    implicit val sys = system
    implicit val mat = materializer

    import system.dispatcher
    val r: server.Route = RouteImplementation(route)
    Http(system).bind(interface, port).toMat(Sink.foreach(_.handleWith(r)))(Keep.left).run()(materializer)
  }
}

/**
 * Provides the entrypoints to create an Http server from a route.
 */
object HttpService extends HttpServiceBase
