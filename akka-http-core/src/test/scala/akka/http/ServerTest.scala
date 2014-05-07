/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.concurrent.duration._
import waves.Flow
import akka.io.IO
import akka.util.Timeout
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.http.model._
import HttpMethods._

object ServerTest extends App {
  implicit val system = ActorSystem()
  import system.dispatcher
  import system.log

  val requestHandler: HttpRequest ⇒ HttpResponse = {
    case HttpRequest(GET, Uri.Path("/"), _, _, _)      ⇒ index
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
    case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
    case _: HttpRequest                                ⇒ HttpResponse(404, "Unknown resource!")
  }

  implicit val askTimeout: Timeout = 500.millis
  val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)
  bindingFuture foreach {
    case Http.ServerBinding(localAddress, connectionStream) ⇒
      log.info("Successfully bound to {}", localAddress)
      Flow(connectionStream).drain {
        case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
          log.info("Accepted new connection from {}", remoteAddress)
          Flow(requestProducer).map(requestHandler).produceTo(responseConsumer)
      }
  }

  Console.readLine()
  system.shutdown()

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(MediaTypes.`text/html`,
      <html>
        <body>
          <h1>Say hello to <i>akka-http-core</i>!</h1>
          <p>Defined resources:</p>
          <ul>
            <li><a href="/ping">/ping</a></li>
            <li><a href="/crash">/crash</a></li>
          </ul>
        </body>
      </html>.toString()))
}
