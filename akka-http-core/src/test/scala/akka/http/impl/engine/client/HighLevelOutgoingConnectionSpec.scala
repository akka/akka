/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.http.scaladsl.{ Http, TestUtils }
import akka.http.scaladsl.model._
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

class HighLevelOutgoingConnectionSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF") {
  implicit val materializer = ActorFlowMaterializer()

  "The connection-level client implementation" should {

    "be able to handle 100 pipelined requests across one connection" in {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

      Http().bindAndHandleSync(r ⇒ HttpResponse(entity = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse),
        serverHostName, serverPort)

      val N = 100
      val result = Source(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id"))
        .via(Http().outgoingConnection(serverHostName, serverPort))
        .mapAsync(4)(_.entity.toStrict(1.second))
        .map { r ⇒ val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)

      Await.result(result, 10.seconds) shouldEqual N * (N + 1) / 2
    }

    "be able to handle 100 pipelined requests across 4 connections (client-flow is reusable)" in {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

      Http().bindAndHandleSync(r ⇒ HttpResponse(entity = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse),
        serverHostName, serverPort)

      val connFlow = Http().outgoingConnection(serverHostName, serverPort)

      val C = 4
      val doubleConnection = Flow() { implicit b ⇒
        import FlowGraph.Implicits._

        val bcast = b.add(Broadcast[HttpRequest](C))
        val merge = b.add(Merge[HttpResponse](C))

        for (i ← 0 until C)
          bcast.out(i) ~> connFlow ~> merge.in(i)
        (bcast.in, merge.out)
      }

      val N = 100
      val result = Source(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id"))
        .via(doubleConnection)
        .mapAsync(4)(_.entity.toStrict(1.second))
        .map { r ⇒ val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)

      Await.result(result, 10.seconds) shouldEqual C * N * (N + 1) / 2
    }
  }
}
