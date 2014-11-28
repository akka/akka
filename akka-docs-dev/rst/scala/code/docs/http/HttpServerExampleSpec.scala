/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http

import akka.actor.ActorSystem
import akka.http.model._
import akka.stream.testkit.AkkaSpec

class HttpServerExampleSpec
  extends AkkaSpec("akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox") {
  def ActorSystem(): ActorSystem = system

  "binding example" in {
    //#bind-example
    import akka.http.Http
    import akka.stream.FlowMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = FlowMaterializer()

    val Http.ServerSource(source, serverBindingKey) = Http(system).bind(interface = "localhost", port = 8080)
    source.foreach {
      case Http.IncomingConnection(remoteAddress, flow) ⇒
        println("Accepted new connection from " + remoteAddress)

      // handle connection here
    }
    //#bind-example
  }

  "full-server-example" in {
    import akka.http.Http
    import akka.stream.FlowMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = FlowMaterializer()

    val Http.ServerSource(source, serverBindingKey) = Http(system).bind(interface = "localhost", port = 8080)

    //#full-server-example
    import akka.http.model.HttpMethods._
    import akka.stream.scaladsl.Flow

    val requestHandler: HttpRequest ⇒ HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) ⇒
        HttpResponse(
          entity = HttpEntity(MediaTypes.`text/html`,
            "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
      case _: HttpRequest                                ⇒ HttpResponse(404, entity = "Unknown resource!")
    }

    // ...

    source.foreach {
      case Http.IncomingConnection(remoteAddress, flow) ⇒
        println("Accepted new connection from " + remoteAddress)

        flow.join(Flow[HttpRequest].map(requestHandler)).run()
    }
    //#full-server-example
  }
}
