/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.impl.engine.ws

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span.convertDurationToSpan
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.apply
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.scaladsl.GraphDSL.Implicits._
import org.scalatest.concurrent.Eventually
import akka.stream.io.SslTlsPlacebo
import java.net.InetSocketAddress
import akka.stream.impl.fusing.GraphStages
import akka.util.ByteString

class WebsocketIntegrationSpec extends AkkaSpec("akka.stream.materializer.debug.fuzzing-mode=off")
  with ScalaFutures with ConversionCheckedTripleEquals with Eventually {

  implicit val patience = PatienceConfig(3.seconds)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  "A Websocket server" must {

    "echo 100 elements and then shut down without error" in Utils.assertAllStagesStopped {
      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebsocket ⇒ u }.get
          upgrade.handleMessages(Flow.apply, None)
      }, interface = "localhost", port = 8080)
      val binding = Await.result(bindingFuture, 3.seconds)

      val N = 100
      val (response, count) = Http().singleWebsocketRequest(
        WebsocketRequest("ws://127.0.0.1:8080"),
        Flow.fromSinkAndSourceMat(
          Sink.fold(0)((n, _: Message) ⇒ n + 1),
          Source.repeat(TextMessage("hello")).take(N))(Keep.left))

      count.futureValue should ===(N)
      binding.unbind()
    }

    "send back 100 elements and then terminate without error even when not ordinarily closed" in Utils.assertAllStagesStopped {
      val N = 100

      val handler = Flow.fromGraph(GraphDSL.create() { implicit b ⇒
        val merge = b.add(Merge[Int](2))

        // convert to int so we can connect to merge
        val mapMsgToInt = b.add(Flow[Message].map(_ ⇒ -1))
        val mapIntToMsg = b.add(Flow[Int].map(x ⇒ TextMessage.Strict(s"Sending: $x")))

        // source we want to use to send message to the connected websocket sink
        val rangeSource = b.add(Source(1 to N))

        mapMsgToInt ~> merge // this part of the merge will never provide msgs
        rangeSource ~> merge ~> mapIntToMsg

        FlowShape(mapMsgToInt.in, mapIntToMsg.out)
      })

      val bindingFuture = Http().bindAndHandleSync({
        case HttpRequest(_, _, headers, _, _) ⇒
          val upgrade = headers.collectFirst { case u: UpgradeToWebsocket ⇒ u }.get
          upgrade.handleMessages(handler, None)
      }, interface = "localhost", port = 8080)
      val binding = Await.result(bindingFuture, 3.seconds)

      @volatile var messages = 0
      val (breaker, completion) =
        Source.maybe
          .viaMat {
            Http().websocketClientLayer(WebsocketRequest("ws://localhost:8080"))
              .atop(SslTlsPlacebo.forScala)
              // the resource leak of #19398 existed only for severed websocket connections
              .atopMat(GraphStages.bidiBreaker[ByteString, ByteString])(Keep.right)
              .join(Tcp().outgoingConnection(new InetSocketAddress("localhost", 8080), halfClose = true))
          }(Keep.right)
          .toMat(Sink.foreach(_ ⇒ messages += 1))(Keep.both)
          .run()
      eventually(messages should ===(N))
      // breaker should have been fulfilled long ago
      breaker.value.get.get.complete()
      completion.futureValue

      binding.unbind()
    }

  }

}
