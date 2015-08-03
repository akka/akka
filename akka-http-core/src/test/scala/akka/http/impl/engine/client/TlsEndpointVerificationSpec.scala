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

class TlsEndpointVerificationSpec extends AkkaSpec("""
    #akka.loggers = []
    akka.loglevel = DEBUG
    akka.io.tcp.trace-logging = off
    akka.io.tcp.windows-connection-abort-workaround-enabled=auto""") with ScalaFutures {
  implicit val materializer = ActorMaterializer()
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

      whenReady(pipe(HttpRequest(uri = "https://akka.example.org/")), timeout) { response ⇒
        response.status shouldEqual StatusCodes.OK
      }
    }
    "not accept certificates for foreign hosts" in {
      val pipe = pipeline(ExampleHttpContexts.exampleClientContext, hostname = "hijack.de") // example context does include custom CA

      whenReady(pipe(HttpRequest(uri = "https://hijack.de/")).failed, timeout) { e ⇒
        e shouldBe an[Exception]
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
