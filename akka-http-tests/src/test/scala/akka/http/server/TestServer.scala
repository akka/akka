/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.io.IO
import akka.stream.FlowMaterializer
import akka.util.Timeout
import akka.pattern.ask
import akka.http.Http
import akka.http.model._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
                                                   """)
  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = FlowMaterializer()

  implicit val askTimeout: Timeout = 500.millis
  val bindingFuture = (IO(Http) ? Http.Bind(interface = "localhost", port = 8080)).mapTo[Http.ServerBinding]

  import ScalaRoutingDSL._

  handleConnections(bindingFuture) withRoute {
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
