/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http

import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

import akka.Done
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.impl.engine.parsing.{ HttpHeaderParser, HttpRequestParser }
import akka.http.scaladsl.settings.ParserSettings
import akka.event.NoLogging
import akka.stream.ActorMaterializer
import akka.stream.TLSProtocol.SessionBytes
import akka.stream.scaladsl._
import akka.util.ByteString
import org.openjdk.jmh.annotations.{ OperationsPerInvocation, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpRequestParsingBenchmark {

  implicit val system: ActorSystem = ActorSystem("HttpRequestParsingBenchmark")
  implicit val materializer = ActorMaterializer()(system)
  val parserSettings = ParserSettings(system)
  val parser = new HttpRequestParser(parserSettings, false, HttpHeaderParser(parserSettings, NoLogging)())
  val dummySession = SSLContext.getDefault.createSSLEngine.getSession

  @Param(Array("small", "large"))
  var req: String = ""

  def request = req match {
    case "small" => requestBytesSmall
    case "large" => requestBytesLarge
  }

  val requestBytesSmall: SessionBytes = SessionBytes(
    dummySession,
    ByteString(
      """|GET / HTTP/1.1
         |Accept: */*
         |Accept-Encoding: gzip, deflate
         |Connection: keep-alive
         |Host: example.com
         |User-Agent: HTTPie/0.9.3
         |
         |""".stripMargin.replaceAll("\n", "\r\n")
    )
  )

  val requestBytesLarge: SessionBytes = SessionBytes(
    dummySession,
    ByteString(
      """|GET /json HTTP/1.1
         |Host: server
         |User-Agent: Mozilla/5.0 (X11; Linux x86_64) Gecko/20130501 Firefox/30.0 AppleWebKit/600.00 Chrome/30.0.0000.0 Trident/10.0 Safari/600.00
         |Cookie: uid=12345678901234567890; __utma=1.1234567890.1234567890.1234567890.1234567890.12; wd=2560x1600
         |Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
         |Accept-Language: en-US,en;q=0.5
         |Connection: keep-alive
         |
         |""".stripMargin.replaceAll("\n", "\r\n")
    )
  )

  /*
  // before:
  [info] Benchmark                                                 (req)   Mode  Cnt            Score       Error  Units
  [info] HttpRequestParsingBenchmark.parse_10000_requests          small  thrpt   20      358 982.157 ± 93745.863  ops/s
  [info] HttpRequestParsingBenchmark.parse_10000_requests          large  thrpt   20      388 335.666 ± 16990.715  ops/s
  
  // after:
  [info] HttpRequestParsingBenchmark.parse_10000_requests_val      small  thrpt   20      623 975.879 ± 6191.897  ops/s
  [info] HttpRequestParsingBenchmark.parse_10000_requests_val      large  thrpt   20      507 460.283 ± 4735.843  ops/s
  */

  val httpMessageParser = Flow.fromGraph(parser)

  def flow(bytes: SessionBytes, n: Int): RunnableGraph[Future[Done]] =
    Source.repeat(request).take(n)
      .via(httpMessageParser)
      .toMat(Sink.ignore)(Keep.right)

  @Benchmark
  @OperationsPerInvocation(10000)
  def parse_10000_requests_val(blackhole: Blackhole): Unit = {
    val done = flow(requestBytesSmall, 10000).run()
    Await.ready(done, 32.days)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }
}
