/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http

import akka.stream.testkit.AkkaSpec
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.duration._
import akka.http.model._

class HttpServerExampleSpec
  extends AkkaSpec("akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox") {
  def ActorSystem(): ActorSystem = system

  "binding example" in {
    //#bind-example
    import akka.pattern.ask

    import akka.io.IO
    import akka.http.Http

    import akka.stream.scaladsl.Flow
    import akka.stream.{ MaterializerSettings, FlowMaterializer }

    implicit val system = ActorSystem()
    import system.dispatcher
    val materializer = FlowMaterializer(MaterializerSettings())
    implicit val askTimeout: Timeout = 500.millis

    val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)
    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            println("Accepted new connection from " + remoteAddress)

          // handle connection here
        }.consume(materializer)
    }
    //#bind-example
  }
  "full-server-example" in {
    import akka.pattern.ask

    import akka.io.IO
    import akka.http.Http

    import akka.stream.scaladsl.Flow
    import akka.stream.{ MaterializerSettings, FlowMaterializer }

    implicit val system = ActorSystem()
    import system.dispatcher
    val materializer = FlowMaterializer(MaterializerSettings())
    implicit val askTimeout: Timeout = 500.millis

    val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)

    //#full-server-example
    import HttpMethods._

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
    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach {
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            println("Accepted new connection from " + remoteAddress)

            Flow(requestProducer).map(requestHandler).produceTo(materializer, responseConsumer)
        }.consume(materializer)
    }
    //#full-server-example
  }
}
