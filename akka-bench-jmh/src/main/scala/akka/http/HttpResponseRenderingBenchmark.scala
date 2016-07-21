/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.impl.engine.rendering.{ HttpResponseRendererFactory, ResponseRenderingContext, ResponseRenderingOutput }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Server
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ ActorMaterializer, Attributes, Inlet, SinkShape }
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Try

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpResponseRenderingBenchmark extends HttpResponseRendererFactory(
  serverHeader = Some(Server("Akka HTTP 2.4.x")),
  responseHeaderSizeHint = 64,
  log = NoLogging
) {

  val config = ConfigFactory.parseString(
    """
      akka {
        loglevel = "ERROR"
      }""".stripMargin
  ).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("HttpResponseRenderingBenchmark", config)
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  val requestRendered = ByteString(
    "GET / HTTP/1.1\r\n" +
      "Accept: */*\r\n" +
      "Accept-Encoding: gzip, deflate\r\n" +
      "Connection: keep-alive\r\n" +
      "Host: example.com\r\n" +
      "User-Agent: HTTPie/0.9.3\r\n" +
      "\r\n"
  )

  def TCPPlacebo(requests: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromSinkAndSource(
      Flow[ByteString].takeWhile(it => !(it.utf8String contains "Connection: close")) to Sink.ignore,
      Source.repeat(requestRendered).take(requests)
    )

  def TlsPlacebo = TLSPlacebo()

  val requestRendering: Flow[HttpRequest, String, NotUsed] =
    Http()
      .clientLayer(headers.Host("blah.com"))
      .atop(TlsPlacebo)
      .join {
        Flow[ByteString].map { x ⇒
          val response = s"HTTP/1.1 200 OK\r\nContent-Length: ${x.size}\r\n\r\n"
          ByteString(response) ++ x
        }
      }
      .mapAsync(1)(response => Unmarshal(response).to[String])

  def renderResponse: Future[String] = Source.single(HttpRequest(uri = "/foo"))
    .via(requestRendering)
    .runWith(Sink.head)

  var request: HttpRequest = _
  var pool: Flow[(HttpRequest, Int), (Try[HttpResponse], Int), _] = _

  @TearDown
  def shutdown(): Unit = {
    Await.ready(Http().shutdownAllConnectionPools(), 1.second)
    Await.result(system.terminate(), 5.seconds)
  }

  /*
  [info] Benchmark                                               Mode  Cnt              Score              Error  Units
  [info] HttpResponseRenderingBenchmark.header_date_val         thrpt   20  2 704 169 260 029.906 ± 234456086114.237  ops/s
  
  // def, normal time
  [info] HttpResponseRenderingBenchmark.header_date_def             thrpt   20    178 297 625 609.638 ± 7429280865.659  ops/s
  [info] HttpResponseRenderingBenchmark.response_ok_simple_val      thrpt   20          1 258 119.673 ± 58399.454  ops/s
  [info] HttpResponseRenderingBenchmark.response_ok_simple_def      thrpt   20            687 576.928 ± 94813.618  ops/s
  
  // clock nanos
  [info] HttpResponseRenderingBenchmark.response_ok_simple_clock    thrpt   20          1 676 438.649 ± 33976.590  ops/s
  [info] HttpResponseRenderingBenchmark.response_ok_simple_clock    thrpt   40          1 199 462.263 ± 222226.304  ops/s
   */

  @Benchmark
  @OperationsPerInvocation(10 * 1000)
  def response_ok_simple(blackhole: Blackhole): Unit =
    renderToImpl(blackhole, 10 * 1000).await()

  @Benchmark
  @OperationsPerInvocation(10 * 1000)
  def response_ok_nocontenttype(blackhole: Blackhole): Unit =
    renderToImpl(blackhole, 10 * 1000).await()

  @Benchmark
  @OperationsPerInvocation(10 * 1000)
  def header_date_clock(): Array[Byte] =
    dateHeader

  val ctx =
    ResponseRenderingContext(
      response = HttpResponse(
        200,
        headers = Nil,
        entity = HttpEntity("ENTITY")
      ),
      requestMethod = HttpMethods.GET
    )

  class JitSafeLatch[A](blackhole: Blackhole, n: Int) extends GraphStageWithMaterializedValue[SinkShape[A], CountDownLatch] {
    val in = Inlet[A]("JitSafeLatch.in")
    override val shape = SinkShape(in)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, CountDownLatch) = {
      val latch = new CountDownLatch(n)
      val logic = new GraphStageLogic(shape) with InHandler {

        override def preStart(): Unit = pull(in)
        override def onPush(): Unit = {
          blackhole.consume(grab(in))
          latch.countDown()
          pull(in)
        }

        setHandler(in, this)
      }

      (logic, latch)
    }
  }

  def renderToImpl(blackhole: Blackhole, n: Int): CountDownLatch = {
    val latch =
      (Source.repeat(ctx).take(n) ++ Source.maybe[ResponseRenderingContext]) // never send upstream completion
        .via(renderer.named("renderer"))
        .runWith(new JitSafeLatch[ResponseRenderingOutput](blackhole, n))

    latch
  }

  override def currentTimeMillis(): Long = System.currentTimeMillis() // DateTime(2011, 8, 25, 9, 10, 29).clicks // provide a stable date for testing

}

//object Render extends HttpResponseRenderingBenchmark {
//  def main(args: Array[String]): Unit = {
//    println("renderToImpl() = " + renderToImpl(null, 2))
//  }
//}
