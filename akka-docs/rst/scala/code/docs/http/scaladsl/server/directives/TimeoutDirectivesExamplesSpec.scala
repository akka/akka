/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Route
import docs.CompileOnlySpec
import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, TestUtils}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import akka.testkit.TestKit

class TimeoutDirectivesExamplesSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures with CompileOnlySpec {
  //#testSetup
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = 1000s""")
  // large timeout - 1000s

  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val patience = PatienceConfig(3.seconds)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def slowFuture(): Future[String] = Promise[String].future

  def runRoute(route: Route, routePath: String): HttpResponse = {
    val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
    val binding = Http().bindAndHandle(route, hostname, port)

    val response = Http().singleRequest(HttpRequest(uri = s"http://$hostname:$port/$routePath")).futureValue

    binding.flatMap(_.unbind()).futureValue

    response
  }

  //#

  "Request Timeout" should {
    "be configurable in routing layer" in {
      //#withRequestTimeout-plain
      val route =
        path("timeout") {
          withRequestTimeout(1.seconds) { // less than akka.http.server.request-timeout value of 1000s
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      runRoute(route, "timeout").status should ===(StatusCodes.ServiceUnavailable) // the timeout response
      //#
    }
    "without timeout" in compileOnlySpec {
      //#withoutRequestTimeout-1
      val route =
        path("timeout") {
          withoutRequestTimeout {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      // no check as there is no time-out, the future will time out failing the test
      //#
    }

    "allow mapping the response while setting the timeout" in {
      //#withRequestTimeout-with-handler
      val timeoutResponse = HttpResponse(
        StatusCodes.EnhanceYourCalm,
        entity = "Unable to serve response within time limit, please enchance your calm.")

      val route =
        path("timeout") {
          // updates timeout and handler at
          withRequestTimeout(1.milli, request => timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

      runRoute(route, "timeout").status should ===(StatusCodes.EnhanceYourCalm) // the timeout response
      //#
    }

    "allow mapping the response" in {
      //#withRequestTimeoutResponse
      val timeoutResponse = HttpResponse(
        StatusCodes.EnhanceYourCalm,
        entity = "Unable to serve response within time limit, please enchance your calm.")

      val route =
        path("timeout") {
          withRequestTimeout(100.milli) { // racy! for a very short timeout like 1.milli you can still get 503
            withRequestTimeoutResponse(request => timeoutResponse) {
              val response: Future[String] = slowFuture() // very slow
              complete(response)
            }
          }
        }

      runRoute(route, "timeout").status should ===(StatusCodes.EnhanceYourCalm) // the timeout response
      //#
    }
  }

}
