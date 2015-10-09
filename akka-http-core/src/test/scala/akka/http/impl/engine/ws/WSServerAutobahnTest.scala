/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.ws.{ Message, UpgradeToWebsocket }
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow

object WSServerAutobahnTest extends App {
  implicit val system = ActorSystem("WSServerTest")
  implicit val fm = ActorMaterializer()

  try {
    val binding = Http().bindAndHandleSync({
      case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) if req.header[UpgradeToWebsocket].isDefined ⇒
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(echoWebsocketService) // needed for running the autobahn test suite
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
    },
      interface = "172.17.42.1", // adapt to your docker host IP address if necessary
      port = 9001)

    Await.result(binding, 1.second) // throws if binding fails
    println("Server online at http://172.17.42.1:9001")
    println("Press RETURN to stop...")
    Console.readLine()
  } finally {
    system.shutdown()
  }

  def echoWebsocketService: Flow[Message, Message, Unit] =
    Flow[Message] // just let message flow directly to the output
}
