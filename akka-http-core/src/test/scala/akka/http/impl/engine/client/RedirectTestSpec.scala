/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Location, RawHeader }
import akka.http.scaladsl.settings.{ ClientAutoRedirectSettings, ClientConnectionSettings }
import akka.http.scaladsl.{ Http, TestUtils }
import akka.stream.scaladsl._
import akka.stream.testkit.Utils
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.duration._

class RedirectTestSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withFuzzing(true))

  "The connection-level client implementation" should {

    "be able to handle redirects" in Utils.assertAllStagesStopped {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()
      val binding = Http().bindAndHandleSync(r ⇒ {
        val c = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse.toInt
        if (c % 2 == 0) {
          HttpResponse(entity = c.toString)
        } else {
          HttpResponse(status = StatusCodes.MovedPermanently, headers = List(Location(s"/r${c + 1}")))
        }
      }, serverHostName, serverPort)

      val N = 100
      val result = Source.fromIterator(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id"))
        .via(Http().outgoingConnection(serverHostName, serverPort))
        .mapAsync(4)(_.entity.toStrict(1.second))
        .map { r ⇒ val s = r.data.utf8String; log.debug(s); s.toInt }
        .runFold(0)(_ + _)

      result.futureValue(PatienceConfig(10.seconds)) shouldEqual (N + 2) * (N / 2) // (2*2) + (4*2) + ... + (100 * 2)
      binding.futureValue.unbind()

    }

    "be able to handle infinite redirect loop" in Utils.assertAllStagesStopped {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()
      val cycleLength = 3
      val binding = Http().bindAndHandleSync(r ⇒ {
        val c = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse.toInt
        HttpResponse(status = StatusCodes.MovedPermanently, headers = List(Location(s"/r${(c % cycleLength) + 1}")))
      }, serverHostName, serverPort)

      val x = Source.fromIterator(() ⇒ Iterator.from(1))
        .take(cycleLength)
        .map(id ⇒ HttpRequest(uri = s"/r$id"))
        .via(Http().outgoingConnection(serverHostName, serverPort))
        .runWith(Sink.head)

      a[RedirectSupportStage.InfiniteRedirectLoopException.type] should be thrownBy Await.result(x, 3.second)

      binding.futureValue.unbind()
    }

    "be able to forward selected headers to redirected requests in same origin" in Utils.assertAllStagesStopped {
      val (_, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()
      println(serverHostName)
      val binding = Http().bindAndHandleSync(r ⇒ {
        val c = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse.toInt
        if (c % 2 == 0) {
          HttpResponse(headers = List(headers.ETag(r.headers.find(_.is("id")).map(_.value).getOrElse("0"))))
        } else {
          HttpResponse(status = StatusCodes.MovedPermanently, headers = List(Location(s"/r${c + 1}")))
        }
      }, serverHostName, serverPort)

      val N = 100
      val result = Source.fromIterator(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id", headers = List(RawHeader("id", id.toString))))
        .via(Http().outgoingConnection(serverHostName, serverPort))
        .map(p ⇒ {
          if (p.status.isRedirection) throw new Exception("No redirects here!")
          println(p.headers)
          p.headers.find(_.is("etag")).map(_.value.replace("\"", "").toInt).getOrElse(0)
        })
        .runFold(0)(_ + _)

      result.futureValue(PatienceConfig(1000.seconds)) shouldEqual (N + 1) * (N / 2)

      binding.futureValue.unbind()
    }

    "be able to forward selected headers to redirected requests in different origin" /*in Utils.assertAllStagesStopped */ ignore {
      val (ipAddress, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()
      println(serverHostName)
      val binding = Http().bindAndHandleSync(r ⇒ {
        val c = r.uri.toString.reverse.takeWhile(Character.isDigit).reverse.toInt
        if (c % 2 == 0) {
          HttpResponse(headers = List(headers.ETag(r.headers.find(_.is("id")).map(_.value).getOrElse("0"))))
        } else {
          val res = HttpResponse(
            status = StatusCodes.MovedPermanently,
            headers = List(Location(s"http://${ipAddress.getAddress.getHostAddress}:$serverPort/r${c + 1}")))
          res
        }
      }, serverHostName, serverPort)

      val N = 100
      val result = Source.fromIterator(() ⇒ Iterator.from(1))
        .take(N)
        .map(id ⇒ HttpRequest(uri = s"/r$id", headers = List(RawHeader("id", id.toString))))
        .via(Http().outgoingConnection(
          serverHostName,
          serverPort,
          settings =
            ClientConnectionSettings(system)
              .withRedirectSettings(
                ClientAutoRedirectSettings("akka.http.client.redirect.cross-origin.allow = true"))))
        .map(p ⇒ {
          println(p.headers)
          p.headers.find(_.is("etag")).map(_.value.replace("\"", "").toInt).getOrElse(0)
        })
        .runFold(0)(_ + _)

      result.futureValue(PatienceConfig(100.seconds)) shouldEqual (N + 2) * (N / 4) // all even numbers till N
      binding.futureValue.unbind()
    }
  }
}
