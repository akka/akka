/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.actor.ActorSystem
import akka.http.engine.ws.{ UpgradeToWebsocket, TextMessage, Message }
import akka.http.model._
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{ Source, Flow }
import com.typesafe.config.{ ConfigFactory, Config }
import HttpMethods._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    """)
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorFlowMaterializer()

  val binding = Http().bind(interface = "localhost", port = 8080)

  binding startHandlingWithSyncHandler {
    case HttpRequest(GET, Uri.Path("/"), _, _, _)      ⇒ index
    case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
    case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
    case req @ HttpRequest(GET, Uri.Path("/ws-greeter"), _, _, _) ⇒
      req.header[UpgradeToWebsocket] match {
        case Some(upgrade) ⇒ upgrade.handleMessages(greeterWebsocketService)
        case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
      }
    case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
  }

  println(s"Server online at http://localhost:8080")
  println("Press RETURN to stop...")
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

  def greeterWebsocketService: Flow[Message, Message] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(name)         ⇒ TextMessage.Strict(s"Hello '$name'")
        case TextMessage.Streamed(nameStream) ⇒ TextMessage.Streamed(Source.single("Hello ") ++ nameStream)
        // ignore binary messages
      }
}
