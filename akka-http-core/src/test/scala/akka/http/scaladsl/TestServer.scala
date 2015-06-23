/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl

import scala.concurrent.duration._
import scala.concurrent.Await
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Flow }
import com.typesafe.config.{ ConfigFactory, Config }
import HttpMethods._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorMaterializer()

  try {
    val binding = Http().bindAndHandleSync({
      case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) if req.header[UpgradeToWebsocket].isDefined ⇒
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(echoWebsocketService) // needed for running the autobahn test suite
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case HttpRequest(GET, Uri.Path("/"), _, _, _)      ⇒ index
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
      case req @ HttpRequest(GET, Uri.Path("/ws-greeter"), _, _, _) ⇒
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(greeterWebsocketService)
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
    }, interface = "localhost", port = 9001)

    Await.result(binding, 1.second) // throws if binding fails
    println("Server online at http://localhost:9001")
    println("Press RETURN to stop...")
    Console.readLine()
  } finally {
    system.shutdown()
  }

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

  def echoWebsocketService: Flow[Message, Message, Unit] =
    Flow[Message] // just let message flow directly to the output

  def greeterWebsocketService: Flow[Message, Message, Unit] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(name) ⇒ TextMessage(s"Hello '$name'")
        case tm: TextMessage          ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream)
        // ignore binary messages
      }
}
