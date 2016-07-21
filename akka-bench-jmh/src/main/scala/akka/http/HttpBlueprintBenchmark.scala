/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.impl.util.ByteStringRendering
import akka.http.scaladsl.{ Http, HttpExt }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling._
import akka.stream._
import akka.stream.TLSProtocol.{ SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.Try

/*
Baseline: 
 
 [info] Benchmark                                      Mode  Cnt       Score       Error  Units
 [info] HttpBlueprintBenchmark.run_10000_reqs         thrpt   20  197972.659 ± 14512.694  ops/s
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpBlueprintBenchmark {

  val config = ConfigFactory.parseString(
    """
      akka {
        loglevel = "WARNING"
        
        stream.materializer {
        
          # default: sync-processing-limit = 1000
          sync-processing-limit = 1000
          
          # default: output-burst-limit = 10000
          output-burst-limit = 1000
          
          # default: initial-input-buffer-size = 4
          initial-input-buffer-size = 4
          
          # default: max-input-buffer-size = 16
          max-input-buffer-size = 16
          
        }
        
        http {
          # default: request-timeout = 20s 
          request-timeout = infinite # disabled
          # request-timeout = 20s 
        }
      }""".stripMargin
  ).withFallback(ConfigFactory.load())

  implicit val system: ActorSystem = ActorSystem("HttpBenchmark", config)

  val materializer: ActorMaterializer = ActorMaterializer()
  val notFusingMaterializer = ActorMaterializer(materializer.settings.withAutoFusing(false))

  val request: HttpRequest = HttpRequest()
  val requestRendered = ByteString(
    "GET / HTTP/1.1\r\n" +
      "Accept: */*\r\n" +
      "Accept-Encoding: gzip, deflate\r\n" +
      "Connection: keep-alive\r\n" +
      "Host: example.com\r\n" +
      "User-Agent: HTTPie/0.9.3\r\n" +
      "\r\n"
  )

  val response: HttpResponse = HttpResponse()
  val responseRendered: ByteString = ByteString(
    s"HTTP/1.1 200 OK\r\n" +
      s"Content-Length: 0\r\n" +
      s"\r\n"
  )

  def TCPPlacebo(requests: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromSinkAndSource(
      Flow[ByteString].takeWhile(it => !(it.utf8String contains "Connection: close")) to Sink.ignore,
      Source.repeat(requestRendered).take(requests)
    )

  def layer: BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] = Http().serverLayer()(materializer)
  def server(requests: Int): Flow[HttpResponse, HttpRequest, _] = layer atop TLSPlacebo() join TCPPlacebo(requests)

  val reply = Flow[HttpRequest].map { _ => response }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  val nothingHere: Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow.fromSinkAndSource(Sink.cancelled, Source.empty)

  @Benchmark
  @OperationsPerInvocation(100000)
  def run_10000_reqs() = {
    val n = 100000
    val latch = new CountDownLatch(n)

    val replyCountdown = reply map { x =>
      latch.countDown()
      x
    }
    server(n).joinMat(replyCountdown)(Keep.right).run()(materializer)

    latch.await()
  }

}

