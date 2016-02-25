/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.impl.util.One2OneBidiFlow

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.{ ActorMaterializerSettings, FlowShape, ActorMaterializer }
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import akka.http.scaladsl.{ Http, TestUtils }
import akka.http.scaladsl.model._
import akka.stream.testkit.Utils
import org.scalatest.concurrent.ScalaFutures

class HighLevelOutgoingConnectionSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withFuzzing(true))

  "The connection-level client implementation" should {

    "be able to handle 100 pipelined requests across one connection" in Utils.assertAllStagesStopped {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

      val binding = Http().bindAndHandleSync(r ⇒ HttpResponse(entity = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse),
        serverHostName, serverPort)

      val N = 100
      val result = Source.fromIterator(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id"))
        .via(Http().outgoingConnection(serverHostName, serverPort))
        .mapAsync(4)(_.entity.toStrict(1.second))
        .map { r ⇒ val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)

      result.futureValue(PatienceConfig(10.seconds)) shouldEqual N * (N + 1) / 2
      binding.futureValue.unbind()
    }

    "be able to handle 100 pipelined requests across 4 connections (client-flow is reusable)" in Utils.assertAllStagesStopped {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

      val binding = Http().bindAndHandleSync(r ⇒ HttpResponse(entity = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse),
        serverHostName, serverPort)

      val connFlow = Http().outgoingConnection(serverHostName, serverPort)

      val C = 4
      val doubleConnection = Flow.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._

        val bcast = b.add(Broadcast[HttpRequest](C))
        val merge = b.add(Merge[HttpResponse](C))

        for (i ← 0 until C)
          bcast.out(i) ~> connFlow ~> merge.in(i)
        FlowShape(bcast.in, merge.out)
      })

      val N = 100
      val result = Source.fromIterator(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id"))
        .via(doubleConnection)
        .mapAsync(4)(_.entity.toStrict(1.second))
        .map { r ⇒ val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)

      result.futureValue(PatienceConfig(10.seconds)) shouldEqual C * N * (N + 1) / 2
      binding.futureValue.unbind()
    }

    "catch response stream truncation" in Utils.assertAllStagesStopped {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

      val binding = Http().bindAndHandleSync({
        case HttpRequest(_, Uri.Path("/b"), _, _, _) ⇒ HttpResponse(headers = List(headers.Connection("close")))
        case _                                       ⇒ HttpResponse()
      }, serverHostName, serverPort)

      val x = Source(List("/a", "/b", "/c"))
        .map(path ⇒ HttpRequest(uri = path))
        .via(Http().outgoingConnection(serverHostName, serverPort))
        .grouped(10)
        .runWith(Sink.head)

      a[One2OneBidiFlow.OutputTruncationException.type] should be thrownBy Await.result(x, 3.second)
      binding.futureValue.unbind()
    }

  }
}
