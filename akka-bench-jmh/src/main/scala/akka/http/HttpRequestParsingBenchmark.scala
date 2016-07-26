/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import javax.net.ssl.SSLContext

import akka.Done
import akka.actor.ActorSystem
import akka.http.impl.engine.parsing.ParserOutput.RequestOutput
import akka.http.impl.engine.parsing.{ HttpHeaderParser, HttpMessageParser, HttpRequestParser }
import akka.http.scaladsl.settings.ParserSettings
import akka.event.NoLogging
import akka.stream.TLSProtocol.SessionBytes
import akka.stream.scaladsl.RunnableGraph
import akka.stream.{ ActorMaterializer, Attributes }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.ByteString
import org.openjdk.jmh.annotations.{ OperationsPerInvocation, _ }
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpRequestParsingBenchmark {

  implicit val system: ActorSystem = ActorSystem("HttpRequestParsingBenchmark")
  implicit val materializer = ActorMaterializer()
  val parserSettings = ParserSettings(system)
  val parser = new HttpRequestParser(parserSettings, false, HttpHeaderParser(parserSettings, NoLogging)())
  val dummySession = SSLContext.getDefault.createSSLEngine.getSession
  val requestBytes = SessionBytes(
    dummySession,
    ByteString(
      "GET / HTTP/1.1\r\n" +
        "Accept: */*\r\n" +
        "Accept-Encoding: gzip, deflate\r\n" +
        "Connection: keep-alive\r\n" +
        "Host: example.com\r\n" +
        "User-Agent: HTTPie/0.9.3\r\n" +
        "\r\n"
    )
  )

  val httpMessageParser = Flow.fromGraph(parser)

  def flow(n: Int): RunnableGraph[Future[Done]] =
    Source.repeat(requestBytes).take(n)
      .via(httpMessageParser)
      .toMat(Sink.ignore)(Keep.right)

  @Benchmark
  @OperationsPerInvocation(10000)
  def parse_10000_single_requests(blackhole: Blackhole): Unit = {
    val done = flow(10000).run()
    Await.ready(done, 32.days)
  }

  @Benchmark
  @OperationsPerInvocation(1000)
  def parse_1000_single_requests(blackhole: Blackhole): Unit = {
    val done = flow(1000).run()
    Await.ready(done, 32.days)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def parse_100_single_requests(blackhole: Blackhole): Unit = {
    val done = flow(100).run()
    Await.ready(done, 32.days)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }
}
