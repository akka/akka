/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.{ Http, TestUtils }
import akka.stream.ActorMaterializer
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

class WithoutSizeLimitSpec extends WordSpec with Matchers with RequestBuilding with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    akka.http.parsing.max-content-length = 800""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  "the withoutSizeLimit directive" should {
    "accept entities bigger than configured with akka.http.parsing.max-content-length" in {
      val route =
        path("noDirective") {
          post {
            entity(as[String]) { _ ⇒
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
            }
          }
        } ~
          path("withoutSizeLimit") {
            post {
              withoutSizeLimit {
                entity(as[String]) { _ ⇒
                  complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
                }
              }
            }
          }

      val (_, hostName, port) = TestUtils.temporaryServerHostnameAndPort()

      val future = for {
        _ ← Http().bindAndHandle(route, hostName, port)

        requestToNoDirective = Post(s"http://$hostName:$port/noDirective", entityOfSize(801))
        responseWithoutDirective ← Http().singleRequest(requestToNoDirective)
        _ = responseWithoutDirective.status shouldEqual StatusCodes.BadRequest

        requestToDirective = Post(s"http://$hostName:$port/withoutSizeLimit", entityOfSize(801))
        responseWithDirective ← Http().singleRequest(requestToDirective)
      } yield responseWithDirective

      val response = Await.result(future, 5 seconds)
      response.status shouldEqual StatusCodes.OK
    }
  }

  override def afterAll() = {
    system.terminate
  }

  private def entityOfSize(size: Int) = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "0" * size)
}
