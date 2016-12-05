/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl

import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

class WebSocketClientExampleSpec extends WordSpec with Matchers with CompileOnlySpec {

  "singleWebSocket-request-example" in compileOnlySpec {
    //#single-WebSocket-request
    import akka.actor.ActorSystem
    import akka.{ Done, NotUsed }
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.model.ws._

    import scala.concurrent.Future

    object SingleWebSocketRequest {
      def main(args: Array[String]) = {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        import system.dispatcher

        // print each incoming strict text message
        val printSink: Sink[Message, Future[Done]] =
          Sink.foreach {
            case message: TextMessage.Strict =>
              println(message.text)
          }

        val helloSource: Source[Message, NotUsed] =
          Source.single(TextMessage("hello world!"))

        // the Future[Done] is the materialized value of Sink.foreach
        // and it is completed when the stream completes
        val flow: Flow[Message, Message, Future[Done]] =
          Flow.fromSinkAndSourceMat(printSink, helloSource)(Keep.left)

        // upgradeResponse is a Future[WebSocketUpgradeResponse] that
        // completes or fails when the connection succeeds or fails
        // and closed is a Future[Done] representing the stream completion from above
        val (upgradeResponse, closed) =
          Http().singleWebSocketRequest(WebSocketRequest("ws://echo.websocket.org"), flow)

        val connected = upgradeResponse.map { upgrade =>
          // just like a regular http request we can access response status which is available via upgrade.response.status
          // status code 101 (Switching Protocols) indicates that server support WebSockets
          if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
            Done
          } else {
            throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
          }
        }

        // in a real application you would not side effect here
        // and handle errors more carefully
        connected.onComplete(println)
        closed.foreach(_ => println("closed"))
      }
    }
    //#single-WebSocket-request
  }

  "half-closed-WebSocket-closing-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.NotUsed
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.ws._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    //#half-closed-WebSocket-closing-example

    // we may expect to be able to to just tail
    // the server websocket output like this
    val flow: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Sink.foreach(println),
        Source.empty)

    Http().singleWebSocketRequest(
      WebSocketRequest("ws://example.com:8080/some/path"),
      flow)

    //#half-closed-WebSocket-closing-example
  }

  "half-closed-WebSocket-working-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.ws._

    import scala.concurrent.Promise

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    //#half-closed-WebSocket-working-example

    // using Source.maybe materializes into a promise
    // which will allow us to complete the source later
    val flow: Flow[Message, Message, Promise[Option[Message]]] =
      Flow.fromSinkAndSourceMat(
        Sink.foreach[Message](println),
        Source.maybe[Message])(Keep.right)

    val (upgradeResponse, promise) =
      Http().singleWebSocketRequest(
        WebSocketRequest("ws://example.com:8080/some/path"),
        flow)

    // at some later time we want to disconnect
    promise.success(None)
    //#half-closed-WebSocket-working-example
  }

  "half-closed-WebSocket-finite-working-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.ws._

    import scala.concurrent.Promise

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    //#half-closed-WebSocket-finite-working-example

    // using emit "one" and "two" and then keep the connection open
    val flow: Flow[Message, Message, Promise[Option[Message]]] =
      Flow.fromSinkAndSourceMat(
        Sink.foreach[Message](println),
        Source(List(TextMessage("one"), TextMessage("two")))
          .concatMat(Source.maybe[Message])(Keep.right))(Keep.right)

    val (upgradeResponse, promise) =
      Http().singleWebSocketRequest(
        WebSocketRequest("ws://example.com:8080/some/path"),
        flow)

    // at some later time we want to disconnect
    promise.success(None)
    //#half-closed-WebSocket-finite-working-example
  }

  "authorized-singleWebSocket-request-example" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.NotUsed
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
    import akka.http.scaladsl.model.ws._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    import collection.immutable.Seq

    val flow: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Sink.foreach(println),
        Source.empty)

    //#authorized-single-WebSocket-request
    val (upgradeResponse, _) =
      Http().singleWebSocketRequest(
        WebSocketRequest(
          "ws://example.com:8080/some/path",
          extraHeaders = Seq(Authorization(
            BasicHttpCredentials("johan", "correcthorsebatterystaple")))),
        flow)
    //#authorized-single-WebSocket-request
  }

  "WebSocketClient-flow-example" in compileOnlySpec {
    //#WebSocket-client-flow
    import akka.actor.ActorSystem
    import akka.Done
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.model.ws._

    import scala.concurrent.Future

    object WebSocketClientFlow {
      def main(args: Array[String]) = {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        import system.dispatcher

        // Future[Done] is the materialized value of Sink.foreach,
        // emitted when the stream completes
        val incoming: Sink[Message, Future[Done]] =
          Sink.foreach[Message] {
            case message: TextMessage.Strict =>
              println(message.text)
          }

        // send this as a message over the WebSocket
        val outgoing = Source.single(TextMessage("hello world!"))

        // flow to use (note: not re-usable!)
        val webSocketFlow = Http().webSocketClientFlow(WebSocketRequest("ws://echo.websocket.org"))

        // the materialized value is a tuple with
        // upgradeResponse is a Future[WebSocketUpgradeResponse] that
        // completes or fails when the connection succeeds or fails
        // and closed is a Future[Done] with the stream completion from the incoming sink
        val (upgradeResponse, closed) =
          outgoing
            .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
            .toMat(incoming)(Keep.both) // also keep the Future[Done]
            .run()

        // just like a regular http request we can access response status which is available via upgrade.response.status
        // status code 101 (Switching Protocols) indicates that server support WebSockets
        val connected = upgradeResponse.flatMap { upgrade =>
          if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
            Future.successful(Done)
          } else {
            throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
          }
        }

        // in a real application you would not side effect here
        connected.onComplete(println)
        closed.foreach(_ => println("closed"))
      }
    }
    //#WebSocket-client-flow
  }

}
