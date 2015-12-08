/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.{ TimeUnit, CountDownLatch }

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.{ TestUtils, Http }
import akka.http.scaladsl.model.{ HttpResponse, Uri, HttpRequest }
import akka.stream.impl.{ StreamSupervisor, ActorMaterializerImpl }
import akka.stream.{ ActorMaterializer, Materializer, OverflowStrategy }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, BeforeAndAfterAll, WordSpecLike }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, Future }
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
  implicit val mat = ActorMaterializer()

  val log = Logging(system, getClass)

  // TODO DUPLICATED
  def assertAllStagesStopped[T](name: String)(block: ⇒ T)(implicit materializer: Materializer): T =
    materializer match {
      case impl: ActorMaterializerImpl ⇒
        val probe = TestProbe()(impl.system)
        probe.send(impl.supervisor, StreamSupervisor.StopChildren)
        probe.expectMsg(StreamSupervisor.StoppedChildren)
        val result = block
        probe.within(5.seconds) {
          probe.awaitAssert {
            impl.supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
            val children = probe.expectMsgType[StreamSupervisor.Children].children.filter { c ⇒
              c.path.toString contains name
            }
            assert(children.isEmpty,
              s"expected no StreamSupervisor children, but got [${children.mkString(", ")}]")
          }
        }
        result
      case _ ⇒ block
    }

  "Http.superPool" should {

    "not leak connection Actors when hitting non-existing endpoint" ignore {
      assertAllStagesStopped("InternalConnectionFlow") {
        val reqsCount = 100
        val clientFlow = Http().superPool[Int]()
        val (_, _, port) = TestUtils.temporaryServerHostnameAndPort()
        val source = Source(1 to reqsCount).map(i ⇒ HttpRequest(uri = Uri(s"http://127.0.0.1:$port/test/$i")) -> i)

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
    system.shutdown()
    system.awaitTermination(3.seconds)
  }

}
