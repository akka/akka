/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi

import akka.http.Http.ServerBinding

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.server.japi.impl.RouteImplementation
import akka.stream.FlowMaterializer
import akka.stream.javadsl.MaterializedMap

trait HttpServiceBase {
  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem): MaterializedMap = {
    implicit val sys = system
    implicit val mat = FlowMaterializer()
    handleConnectionsWithRoute(Http(system).bind(interface, port), route, system, mat)
  }

  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem, flowMaterializer: FlowMaterializer): MaterializedMap =
    handleConnectionsWithRoute(Http(system).bind(interface, port), route, system, flowMaterializer)

  /**
   * Uses the route to handle incoming connections and requests for the ServerBinding.
   */
  def handleConnectionsWithRoute(serverBinding: ServerBinding, route: Route, system: ActorSystem, flowMaterializer: FlowMaterializer): MaterializedMap = {
    implicit val sys = system
    implicit val mat = flowMaterializer

    import system.dispatcher
    val map = serverBinding.startHandlingWith(RouteImplementation(route))
    new MaterializedMap(map)
  }
}

/**
 * Provides the entrypoints to create an Http server from a route.
 */
object HttpService extends HttpServiceBase