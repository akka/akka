/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import java.util.concurrent.CompletionStage

/**
 * A convenience class to derive from to get everything from HttpService and Directives into scope.
 * Implement the [[#createRoute]] method to provide the Route and then call [[#bindRoute]]
 * to start the server on the specified interface.
 */
abstract class HttpApp
  extends AllDirectives
  with HttpServiceBase {
  def createRoute(): Route

  /**
   * Starts an HTTP server on the given interface and port. Creates the route by calling the
   * user-implemented [[#createRoute]] method and uses the route to handle requests of the server.
   */
  def bindRoute(interface: String, port: Int, system: ActorSystem): CompletionStage[ServerBinding] =
    bindRoute(interface, port, createRoute(), system)
}
