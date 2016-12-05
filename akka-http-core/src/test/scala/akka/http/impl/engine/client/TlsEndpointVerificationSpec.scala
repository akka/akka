/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.NotUsed
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import akka.stream.{ Server, Client, ActorMaterializer }
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import akka.http.impl.util._
import akka.http.scaladsl.{ ConnectionContext, Http }
import akka.http.scaladsl.model.{ StatusCodes, HttpResponse, HttpRequest }
import akka.http.scaladsl.model.headers.{ Host, `Tls-Session-Info` }
import org.scalatest.time.{ Span, Seconds }
import scala.concurrent.Future

class TlsEndpointVerificationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.io.tcp.trace-logging = off
    akka.http.parsing.tls-session-info-header = on
  """) {
  implicit val materializer = ActorMaterializer()

  /*
   * Useful when debugging against "what if we hit a real website"
   */
  val includeTestsHittingActualWebsites = false

  val timeout = Timeout(Span(3, Seconds))

  "The client implementation" should {
    "not accept certificates signed by unknown CA" in {
      val pipe = pipeline(Http().defaultClientHttpsContext, hostname = "akka.example.org") // default context doesn't include custom CA

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org/")).failed, timeout) { e ⇒
        e shouldBe an[Exception]
      }
    }
    "accept certificates signed by known CA" in {
      val pipe = pipeline(ExampleHttpContexts.exampleClientContext, hostname = "akka.example.org") // example context does include custom CA

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org:8080/")), timeout) { response ⇒
        response.status shouldEqual StatusCodes.OK
        val tlsInfo = response.header[`Tls-Session-Info`].get
        tlsInfo.peerPrincipal.get.getName shouldEqual "CN=akka.example.org,O=Internet Widgits Pty Ltd,ST=Some-State,C=AU"
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
        // JDK built-in verification
        val expectedMsg = "No subject alternative DNS name matching www.howsmyssl.com found"

        var e: Throwable = ex
        while (e.getCause != null) e = e.getCause

        info("TLS failure cause: " + e.getMessage)
        e.getMessage should include(expectedMsg)
      }

      "pass hostname verification on https://www.playframework.com/" in {
        val req = HttpRequest(uri = "https://www.playframework.com/")
        val res = Http().singleRequest(req).futureValue
        res.status should ===(StatusCodes.OK)
      }
    }
  }

  def pipeline(clientContext: ConnectionContext, hostname: String): HttpRequest ⇒ Future[HttpResponse] = req ⇒
    Source.single(req).via(pipelineFlow(clientContext, hostname)).runWith(Sink.head)

  def pipelineFlow(clientContext: ConnectionContext, hostname: String): Flow[HttpRequest, HttpResponse, NotUsed] = {
    val handler: HttpRequest ⇒ HttpResponse = { req ⇒
      // verify Tls-Session-Info header information
      val name = req.header[`Tls-Session-Info`].flatMap(_.localPrincipal).map(_.getName)
      if (name.exists(_ == "CN=akka.example.org,O=Internet Widgits Pty Ltd,ST=Some-State,C=AU")) HttpResponse()
      else HttpResponse(StatusCodes.BadRequest, entity = "Tls-Session-Info header verification failed")
    }

    val serverSideTls = Http().sslTlsStage(ExampleHttpContexts.exampleServerContext, Server)
    val clientSideTls = Http().sslTlsStage(clientContext, Client, Some(hostname → 8080))

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
