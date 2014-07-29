/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.duration._
import akka.stream.scaladsl.Flow
import akka.io.IO
import akka.util.Timeout
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.http.model._
import HttpMethods._
import akka.stream.{ MaterializerSettings, FlowMaterializer }

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher

  val requestHandler: HttpRequest ⇒ HttpResponse = {
    case HttpRequest(GET, Uri.Path("/"), _, _, _)      ⇒ index
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
    case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
    case _: HttpRequest                                ⇒ HttpResponse(404, entity = "Unknown resource!")
  }

  val materializer = FlowMaterializer(MaterializerSettings())

  implicit val askTimeout: Timeout = 500.millis
  val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)
  bindingFuture foreach {
    case Http.ServerBinding(localAddress, connectionStream) ⇒
      Flow(connectionStream).foreach {
        case Http.IncomingConnection(remoteAddress, requestPublisher, responseSubscriber) ⇒
          println("Accepted new connection from " + remoteAddress)
          Flow(requestPublisher).map(requestHandler).produceTo(materializer, responseSubscriber)
      }.consume(materializer)
  }

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")

  Console.readLine()
  system.shutdown()

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(MediaTypes.`text/html`,
      """|<html>
         | <body>
         |    <h1>Say hello to <i>akka-http-core</i>!</h1>
         |    <p>Defined resources:</p>
         |    <ul>
         |      <li><a href="/ping">/ping</a></li>
         |      <li><a href="/crash">/crash</a></li>
         |    </ul>
         |  </body>
         |</html>""".stripMargin))
}
