/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.stream.{ OverflowStrategy, ActorMaterializer }
import akka.testkit.TestProbe
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import scala.concurrent.duration._
import akka.testkit.TestKit

class TightRequestTimeoutSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = 10ms""")

  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val patience = PatienceConfig(3.seconds)

  override def afterAll() = TestKit.shutdownActorSystem(system)

  "Tight request timeout" should {

    "not cause double push error caused by the late response attemting to push" in {
      val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
      val slowHandler = Flow[HttpRequest].map(_ ⇒ HttpResponse()).delay(500.millis, OverflowStrategy.backpressure)
      val binding = Http().bindAndHandle(slowHandler, hostname, port)

      val p = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[Logging.Error])

      val response = Http().singleRequest(HttpRequest(uri = s"http://$hostname:$port/")).futureValue
      response.status should ===(StatusCodes.ServiceUnavailable) // the timeout response

      p.expectNoMsg(1.second) // here the double push might happen

      binding.flatMap(_.unbind()).futureValue
    }

  }
}
