/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model.HttpMethods._
import akka.http.model._
import akka.io.IO
import akka.stream.scaladsl.Flow
import akka.stream.{ MaterializerSettings, FlowMaterializer }
import akka.util.Timeout
import com.typesafe.config.{ ConfigFactory, Config }
import akka.pattern.ask
import scala.concurrent.duration._

import scala.concurrent.Future

object TestServerWithRouting extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
                                                   """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = FlowMaterializer(MaterializerSettings())

  implicit val askTimeout: Timeout = 500.millis
  val bindingFuture = (IO(Http) ? Http.Bind(interface = "localhost", port = 8080)).mapTo[Http.ServerBinding]

  import Directives._

  HttpService.handleConnections(bindingFuture) {
    get {
      path("") {
        complete(index)
      } ~
        path("ping") {
          complete("PONG!")
        } ~
        path("crash") {
          complete(sys.error("BOOM!"))
        }
    }
  }

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")

  Console.readLine()
  system.shutdown()

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
