/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.{ server, Http }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.impl.server.RouteImplementation
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.{ Keep, Sink }
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

trait HttpServiceBase {
  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem): CompletionStage[ServerBinding] = {
    implicit val sys = system
    implicit val materializer = ActorMaterializer()
    handleConnectionsWithRoute(interface, port, route, system, materializer)
  }

  /**
   * Starts a server on the given interface and port and uses the route to handle incoming requests.
   */
  def bindRoute(interface: String, port: Int, route: Route, system: ActorSystem, materializer: Materializer): CompletionStage[ServerBinding] =
    handleConnectionsWithRoute(interface, port, route, system, materializer)

  /**
   * Uses the route to handle incoming connections and requests for the ServerBinding.
   */
  def handleConnectionsWithRoute(interface: String, port: Int, route: Route, system: ActorSystem, materializer: Materializer): CompletionStage[ServerBinding] = {
    implicit val s = system
    implicit val m = materializer

    import system.dispatcher
    val r: server.Route = RouteImplementation(route)
    Http(system).bind(interface, port).toMat(Sink.foreach(_.handleWith(akka.http.scaladsl.server.RouteResult.route2HandlerFlow(r))))(Keep.left).run()(materializer).toJava
  }
}

/**
 * Provides the entrypoints to create an Http server from a route.
 */
object HttpService extends HttpServiceBase
