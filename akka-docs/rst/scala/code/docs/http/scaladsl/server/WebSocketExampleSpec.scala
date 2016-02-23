/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.model.ws.BinaryMessage
import akka.stream.scaladsl.Sink
import org.scalatest.{ Matchers, WordSpec }

class WebSocketExampleSpec extends WordSpec with Matchers {
  "core-example" in {
    pending // compile-time only test
    //#websocket-example-using-core
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.{ Source, Flow }
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.ws.UpgradeToWebSocket
    import akka.http.scaladsl.model.ws.{ TextMessage, Message }
    import akka.http.scaladsl.model.{ HttpResponse, Uri, HttpRequest }
    import akka.http.scaladsl.model.HttpMethods._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    //#websocket-handler
    // The Greeter WebSocket Service expects a "name" per message and
    // returns a greeting message for that name
    val greeterWebSocketService =
      Flow[Message]
        .mapConcat {
          // we match but don't actually consume the text message here,
          // rather we simply stream it back as the tail of the response
          // this means we might start sending the response even before the
          // end of the incoming message has been received
          case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream) :: Nil
          case bm: BinaryMessage =>
            // ignore binary messages but drain content to avoid the stream being clogged
            bm.dataStream.runWith(Sink.ignore)
            Nil
        }
    //#websocket-handler

    //#websocket-request-handling
    val requestHandler: HttpRequest ⇒ HttpResponse = {
      case req @ HttpRequest(GET, Uri.Path("/greeter"), _, _, _) ⇒
        req.header[UpgradeToWebSocket] match {
          case Some(upgrade) ⇒ upgrade.handleMessages(greeterWebSocketService)
          case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
        }
      case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
    }
    //#websocket-request-handling

    val bindingFuture =
      Http().bindAndHandleSync(requestHandler, interface = "localhost", port = 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    Console.readLine()

    import system.dispatcher // for the future transformations
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ ⇒ system.terminate()) // and shutdown when done
  }
  "routing-example" in {
    pending // compile-time only test
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
    val greeterWebSocketService =
      Flow[Message]
        .collect {
          case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream)
          // ignore binary messages
        }

    //#websocket-routing
    val route =
      path("greeter") {
        get {
          handleWebSocketMessages(greeterWebSocketService)
        }
      }
    //#websocket-routing

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    Console.readLine()

    import system.dispatcher // for the future transformations
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ ⇒ system.terminate()) // and shutdown when done
  }
}
