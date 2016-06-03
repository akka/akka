/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.{ TimeUnit, CountDownLatch }

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.{ TestUtils, Http }
import akka.http.scaladsl.model.{ HttpResponse, Uri, HttpRequest }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.Utils.assertAllStagesStopped
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, BeforeAndAfterAll, WordSpecLike }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class DontLeakActorsOnFailingConnectionSpecs extends WordSpecLike with Matchers with BeforeAndAfterAll {

  val config = ConfigFactory.parseString("""
    akka {
      # disable logs (very noisy tests - 100 exepected errors)
      loglevel = OFF
      stdout-loglevel = OFF
    }""").withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("DontLeakActorsOnFailingConnectionSpecs", config)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  val log = Logging(system, getClass)

  "Http.superPool" should {

    "not leak connection Actors when hitting non-existing endpoint" in {
      assertAllStagesStopped {
        val reqsCount = 100
        val clientFlow = Http().superPool[Int]()
        val (_, _, port) = TestUtils.temporaryServerHostnameAndPort()
        val source = Source(1 to reqsCount).map(i ⇒ HttpRequest(uri = Uri(s"http://127.0.0.1:$port/test/$i")) → i)

        val countDown = new CountDownLatch(reqsCount)
        val sink = Sink.foreach[(Try[HttpResponse], Int)] {
          case (resp, id) ⇒ handleResponse(resp, id)
        }

        val resps = source.via(clientFlow).runWith(sink)
        resps.onComplete({ case _ ⇒ countDown.countDown() })

        countDown.await(10, TimeUnit.SECONDS)
        Thread.sleep(5000)
      }
    }
  }

  private def handleResponse(httpResp: Try[HttpResponse], id: Int): Unit = {
    httpResp match {
      case Success(httpRes) ⇒
        println(s"$id: OK: (${httpRes.status.intValue}")
        httpRes.entity.dataBytes.runWith(Sink.ignore)

      case Failure(ex) ⇒
        println(s"$id: FAIL: $ex")
    }
  }

  override def afterAll = {
    Await.result(system.terminate(), 3.seconds)
  }

}
