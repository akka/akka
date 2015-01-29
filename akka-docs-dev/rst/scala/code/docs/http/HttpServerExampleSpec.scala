/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http

import akka.actor.ActorSystem
import akka.http.model._
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec

class HttpServerExampleSpec
  extends AkkaSpec("akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox") {
  def ActorSystem(): ActorSystem = system

  "binding example" in {
    //#bind-example
    import akka.http.Http
    import akka.stream.ActorFlowMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = ActorFlowMaterializer()

    val serverBinding = Http(system).bind(interface = "localhost", port = 8080)
    serverBinding.connections.runForeach { connection => // foreach materializes the source
      println("Accepted new connection from " + connection.remoteAddress)
    }
    //#bind-example
  }

  "full-server-example" in {
    import akka.http.Http
    import akka.stream.ActorFlowMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = ActorFlowMaterializer()

    val serverBinding = Http(system).bind(interface = "localhost", port = 8080)

    //#full-server-example
    import akka.http.model.HttpMethods._
    import akka.stream.scaladsl.Flow

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(
          entity = HttpEntity(MediaTypes.`text/html`,
            "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) => sys.error("BOOM!")
      case _: HttpRequest                                => HttpResponse(404, entity = "Unknown resource!")
    }

    serverBinding.connections runForeach { connection =>
      println("Accepted new connection from " + connection.remoteAddress)

      connection handleWithSyncHandler requestHandler
      // this is equivalent to
      // connection handleWith { Flow[HttpRequest] map requestHandler }
    }
    //#full-server-example
  }
}
