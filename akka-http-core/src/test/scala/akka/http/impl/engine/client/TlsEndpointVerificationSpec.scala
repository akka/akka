/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import akka.stream.ActorMaterializer
import akka.stream.io._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import akka.http.impl.util._
import akka.http.scaladsl.{ HttpsContext, Http }
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpRequest }
import akka.http.scaladsl.model.headers.Host
import org.scalatest.time.{ Span, Seconds }
import scala.concurrent.Future
import akka.testkit.EventFilter
import javax.net.ssl.SSLException

class TlsEndpointVerificationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.io.tcp.trace-logging = off
  """) with ScalaFutures {

  implicit val materializer = ActorMaterializer()

  /*
   * Useful when debugging against "what if we hit a real website"
   */
  val includeTestsHittingActualWebsites = false

  val timeout = Timeout(Span(3, Seconds))
  implicit val patience = PatienceConfig(Span(10, Seconds))

  "The client implementation" should {
    "not accept certificates signed by unknown CA" in EventFilter[SSLException](occurrences = 1).intercept {
      val pipe = pipeline(Http().defaultClientHttpsContext, hostname = "akka.example.org") // default context doesn't include custom CA

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org/")).failed, timeout) { e ⇒
        e shouldBe an[Exception]
      }
    }
    "accept certificates signed by known CA" in {
      val pipe = pipeline(ExampleHttpContexts.exampleClientContext, hostname = "akka.example.org") // example context does include custom CA

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org:8080/")), timeout) { response ⇒
        response.status shouldEqual StatusCodes.OK
      }
    }
    "not accept certificates for foreign hosts" in {
      val pipe = pipeline(ExampleHttpContexts.exampleClientContext, hostname = "hijack.de") // example context does include custom CA

      whenReady(pipe(HttpRequest(uri = "https://hijack.de/")).failed, timeout) { e ⇒
        e shouldBe an[Exception]
      }
    }

    if (includeTestsHittingActualWebsites) {
      /*
       * Requires the following DNS spoof to be running:
       * sudo /usr/local/bin/python ./dnschef.py --fakedomains www.howsmyssl.com --fakeip 54.173.126.144
       *
       * Read up about it on: https://tersesystems.com/2014/03/31/testing-hostname-verification/
       */
      "fail hostname verification on spoofed https://www.howsmyssl.com/" in {
        val req = HttpRequest(uri = "https://www.howsmyssl.com/")
        val ex = intercept[Exception] {
          Http().singleRequest(req).futureValue
        }
        if (Java6Compat.isJava6) {
          // our manual verification
          ex.getMessage should include("Hostname verification failed")
        } else {
          // JDK built-in verification
          val expectedMsg = "No subject alternative DNS name matching www.howsmyssl.com found"

          var e: Throwable = ex
          while (e.getCause != null) e = e.getCause

          info("TLS failure cause: " + e.getMessage)
          e.getMessage should include(expectedMsg)
        }
      }

      "pass hostname verification on https://www.playframework.com/" in {
        val req = HttpRequest(uri = "https://www.playframework.com/")
        val res = Http().singleRequest(req).futureValue
        res.status should ===(StatusCodes.OK)
      }
    }
  }

  def pipeline(clientContext: HttpsContext, hostname: String): HttpRequest ⇒ Future[HttpResponse] = req ⇒
    Source.single(req).via(pipelineFlow(clientContext, hostname)).runWith(Sink.head)

  def pipelineFlow(clientContext: HttpsContext, hostname: String): Flow[HttpRequest, HttpResponse, Unit] = {
    val handler: HttpRequest ⇒ HttpResponse = _ ⇒ HttpResponse()

    val serverSideTls = Http().sslTlsStage(Some(ExampleHttpContexts.exampleServerContext), Server)
    val clientSideTls = Http().sslTlsStage(Some(clientContext), Client, Some(hostname -> 8080))

    val server =
      Http().serverLayer()
        .atop(serverSideTls)
        .reversed
        .join(Flow[HttpRequest].map(handler))

    val client =
      Http().clientLayer(Host(hostname, 8080))
        .atop(clientSideTls)

    client.join(server)
  }
}
