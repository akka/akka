/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, Uri }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.Utils.assertAllStagesStopped
import akka.testkit.{ TestKit, SocketUtil }

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

abstract class DontLeakActorsOnFailingConnectionSpecs(poolImplementation: String) extends WordSpecLike with Matchers with BeforeAndAfterAll {

  val config = ConfigFactory.parseString(s"""
    akka {
      # disable logs (very noisy tests - 100 expected errors)
      loglevel = OFF
      stdout-loglevel = OFF

      http.host-connection-pool.pool-implementation = $poolImplementation
    }""").withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("DontLeakActorsOnFailingConnectionSpecs-" + poolImplementation, config)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  val log = Logging(system, getClass)

  "Http.superPool" should {

    "not leak connection Actors when hitting non-existing endpoint" in {
      val address = SocketUtil.temporaryServerAddress()
      assertAllStagesStopped {
        val reqsCount = 100
        val clientFlow = Http().superPool[Int]()
        val host = address.getHostString
        val port = address.getPort
        val source = Source(1 to reqsCount)
          .map(i ⇒ HttpRequest(uri = Uri(s"http://$host:$port/test/$i")) → i)

        val countDown = new CountDownLatch(reqsCount)
        val sink = Sink.foreach[(Try[HttpResponse], Int)] {
          case (resp, id) ⇒
            countDown.countDown()
            handleResponse(resp, id)
        }

        val running = source.via(clientFlow).runWith(sink)
        countDown.await(10, TimeUnit.SECONDS) should be(true)
        Await.result(running, 10.seconds)

      }
    }
  }

  private def handleResponse(httpResp: Try[HttpResponse], id: Int): Unit = {
    httpResp match {
      case Success(httpRes) ⇒
        system.log.info(s"$id: OK: (${httpRes.status.intValue}")
        httpRes.entity.dataBytes.runWith(Sink.ignore)

      case Failure(ex) ⇒
        system.log.error(ex, s"$id: FAIL")
    }
  }

  override def afterAll = TestKit.shutdownActorSystem(system)
}

class LegacyPoolDontLeakActorsOnFailingConnectionSpecs extends DontLeakActorsOnFailingConnectionSpecs("legacy")
class NewPoolDontLeakActorsOnFailingConnectionSpecs extends DontLeakActorsOnFailingConnectionSpecs("new")
