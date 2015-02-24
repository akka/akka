/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi

import akka.actor.ActorSystem
import akka.http.{ server, Http }
import akka.http.Http.ServerBinding
import akka.http.server.japi.impl.RouteImplementation
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Keep, Sink }

import scala.concurrent.Future

trait HttpServiceBase {
  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem): Future[ServerBinding] = {
    implicit val sys = system
    implicit val mat = FlowMaterializer()
    handleConnectionsWithRoute(interface, port, route, system, mat)
  }

  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem, flowMaterializer: FlowMaterializer): Future[ServerBinding] =
    handleConnectionsWithRoute(interface, port, route, system, flowMaterializer)

  /**
   * Uses the route to handle incoming connections and requests for the ServerBinding.
   */
  def handleConnectionsWithRoute(interface: String, port: Int, route: Route, system: ActorSystem, flowMaterializer: FlowMaterializer): Future[ServerBinding] = {
    implicit val sys = system
    implicit val mat = flowMaterializer

    import system.dispatcher
    val r: server.Route = RouteImplementation(route)
    Http(system).bind(interface, port).toMat(Sink.foreach(_.handleWith(r)))(Keep.left).run()(flowMaterializer)
  }
}

/**
 * Provides the entrypoints to create an Http server from a route.
 */
object HttpService extends HttpServiceBase