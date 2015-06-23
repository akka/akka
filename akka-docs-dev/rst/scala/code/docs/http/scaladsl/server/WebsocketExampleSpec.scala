/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server

import org.scalatest.{ Matchers, WordSpec }

class WebsocketExampleSpec extends WordSpec with Matchers {
  "core-example" in {
    pending // compile-time only test
    //#websocket-example-using-core
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.{ Source, Flow }
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.ws.UpgradeToWebsocket
    import akka.http.scaladsl.model.ws.{ TextMessage, Message }
    import akka.http.scaladsl.model.{ HttpResponse, Uri, HttpRequest }
    import akka.http.scaladsl.model.HttpMethods._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    // The Greeter WebSocket Service expects a "name" per message and
    // returns a greeting message for that name
    val greeterWebsocketService =
      Flow[Message]
        .collect {
          // we match but don't actually consume the text message here,
          // rather we simply stream it back as the tail of the response
          // this means we might start sending the response even before the
          // end of the incoming message has been received
          case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream)
          // ignore binary messages
        }

    val bindingFuture = Http().bindAndHandleSync({
      case req @ HttpRequest(GET, Uri.Path("/ws-greeter"), _, _, _) ⇒
        req.header[UpgradeToWebsocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(greeterWebsocketService)
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
    }, interface = "localhost", port = 8080)
    //#websocket-example-using-core

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    Console.readLine()

    import system.dispatcher // for the future transformations
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ ⇒ system.shutdown()) // and shutdown when done
  }
  "routing-example" in {
    pending // compile-time only test
    //#websocket-example-using-routing
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.{ Source, Flow }
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.ws.{ TextMessage, Message }
    import akka.http.scaladsl.server.Directives

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    import Directives._

    // The Greeter WebSocket Service expects a "name" per message and
    // returns a greeting message for that name
    val greeterWebsocketService =
      Flow[Message]
        .collect {
          case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream)
          // ignore binary messages
        }

    val route =
      path("ws-greeter") {
        get {
          handleWebsocketMessages(greeterWebsocketService)
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    Console.readLine()

    import system.dispatcher // for the future transformations
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ ⇒ system.shutdown()) // and shutdown when done
    //#websocket-example-using-routing
  }
}
