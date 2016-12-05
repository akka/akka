/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.NotUsed

import scala.concurrent.duration._
import scala.concurrent.Await
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl.{ Flow, Source }
import com.typesafe.config.{ Config, ConfigFactory }
import HttpMethods._

import scala.io.StdIn

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    akka.actor.serialize-creators = off
    akka.actor.serialize-messages = off
    akka.actor.default-dispatcher.throughput = 1000
    """)
  implicit val system = ActorSystem("ServerTest", testConf)

  val settings = ActorMaterializerSettings(system)
    .withFuzzing(false)
    //    .withSyncProcessingLimit(Int.MaxValue)
    .withInputBuffer(128, 128)
  implicit val fm = ActorMaterializer(settings)
  try {
    val binding = Http().bindAndHandleSync({
      case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) if req.header[UpgradeToWebSocket].isDefined ⇒
        req.header[UpgradeToWebSocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(echoWebSocketService) // needed for running the autobahn test suite
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case HttpRequest(GET, Uri.Path("/"), _, _, _)      ⇒ index
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
      case req @ HttpRequest(GET, Uri.Path("/ws-greeter"), _, _, _) ⇒
        req.header[UpgradeToWebSocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(greeterWebSocketService)
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
    }, interface = "localhost", port = 9001)

    Await.result(binding, 1.second) // throws if binding fails
    println("Server online at http://localhost:9001")
    println("Press RETURN to stop...")
    StdIn.readLine()
  } finally {
    system.terminate()
  }

  ////////////// helpers //////////////

  lazy val index = HttpResponse(
    entity = HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
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

  def echoWebSocketService: Flow[Message, Message, NotUsed] =
    Flow[Message] // just let message flow directly to the output

  def greeterWebSocketService: Flow[Message, Message, NotUsed] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(name) ⇒ TextMessage(s"Hello '$name'")
        case tm: TextMessage          ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream)
        // ignore binary messages
      }
}
