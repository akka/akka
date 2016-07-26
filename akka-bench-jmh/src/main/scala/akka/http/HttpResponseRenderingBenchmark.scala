/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.impl.engine.rendering.ResponseRenderingOutput.HttpData
import akka.http.impl.engine.rendering.{ HttpResponseRendererFactory, ResponseRenderingContext, ResponseRenderingOutput }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Server
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
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
  
  // ------
  
  // before optimisig collectFirst
  [info] HttpResponseRenderingBenchmark.json_response        thrpt   20  1 782 572.845 ± 16572.625  ops/s
  [info] HttpResponseRenderingBenchmark.simple_response      thrpt   20  1 611 802.216 ± 19557.151  ops/s
  
  // after removing collectFirst and Option from renderHeaders
  // not much of a difference, but hey, less Option allocs
  [info] HttpResponseRenderingBenchmark.json_response        thrpt   20  1 785 152.896 ± 15210.299  ops/s
  [info] HttpResponseRenderingBenchmark.simple_response      thrpt   20  1 783 800.184 ± 14938.415  ops/s
  
  // -----

  // baseline for this optimisation is the above results (after collectFirst).
  
  // after introducing pre-rendered ContentType headers:
  [info] Benchmark                                            Mode  Cnt        Score       Error  Units
  [info] HttpResponseRenderingBenchmark.json_response        thrpt   20  2 024 715.373 ± 16 990.783  ops/s
  [info] HttpResponseRenderingBenchmark.simple_response      thrpt   20  1 993 392.529 ± 59 871.846  ops/s
  
   */

  /**
   * HTTP/1.1 200 OK
   * Server: Akka HTTP 2.4.x
   * Date: Tue, 26 Jul 2016 15:26:53 GMT
   * Content-Type: text/plain; charset=UTF-8
   * Content-Length: 6
   *
   * ENTITY
   */
  val simpleResponse =
    ResponseRenderingContext(
      response = HttpResponse(
        200,
        headers = Nil,
        entity = HttpEntity("ENTITY")
      ),
      requestMethod = HttpMethods.GET
    )

  /**
   * HTTP/1.1 200 OK
   * Server: Akka HTTP 2.4.x
   * Date: Tue, 26 Jul 2016 15:26:53 GMT
   * Content-Type: application/json
   * Content-Length: 27
   *
   * {"message":"Hello, World!"}
   */
  val jsonResponse =
    ResponseRenderingContext(
      response = HttpResponse(
        200,
        headers = Nil,
        entity = HttpEntity(ContentTypes.`application/json`, """{"message":"Hello, World!"}""")
      ),
      requestMethod = HttpMethods.GET
    )

  @Benchmark
  @OperationsPerInvocation(100 * 1000)
  def simple_response(blackhole: Blackhole): Unit =
    renderToImpl(simpleResponse, blackhole, n = 100 * 1000).await()

  @Benchmark
  @OperationsPerInvocation(100 * 1000)
  def json_response(blackhole: Blackhole): Unit =
    renderToImpl(jsonResponse, blackhole, n = 100 * 1000).await()

  class JitSafeLatch[A](blackhole: Blackhole, n: Int) extends GraphStageWithMaterializedValue[SinkShape[A], CountDownLatch] {
    val in = Inlet[A]("JitSafeLatch.in")
    override val shape = SinkShape(in)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, CountDownLatch) = {
      val latch = new CountDownLatch(n)
      val logic = new GraphStageLogic(shape) with InHandler {

        override def preStart(): Unit = pull(in)
        override def onPush(): Unit = {
          if (blackhole ne null) blackhole.consume(grab(in))
          latch.countDown()
          pull(in)
        }

        setHandler(in, this)
      }

      (logic, latch)
    }
  }

  def renderToImpl(ctx: ResponseRenderingContext, blackhole: Blackhole, n: Int)(implicit mat: Materializer): CountDownLatch = {
    val latch =
      (Source.repeat(ctx).take(n) ++ Source.maybe[ResponseRenderingContext]) // never send upstream completion
        .via(renderer.named("renderer"))
        .runWith(new JitSafeLatch[ResponseRenderingOutput](blackhole, n))

    latch
  }

  // TODO benchmark with stable override
  override def currentTimeMillis(): Long = System.currentTimeMillis()
  //  override def currentTimeMillis(): Long = System.currentTimeMillis() // DateTime(2011, 8, 25, 9, 10, 29).clicks // provide a stable date for testing

}

//object Render extends HttpResponseRendererFactory(
//  serverHeader = Some(Server("Akka HTTP 2.4.x")),
//  responseHeaderSizeHint = 64,
//  log = NoLogging
//) {
//
//  val config = ConfigFactory.parseString(
//    """
//      akka {
//        loglevel = "ERROR"
//      }""".stripMargin
//  ).withFallback(ConfigFactory.load())
//
//  implicit val system = ActorSystem("HttpResponseRenderingBenchmark", config)
//  implicit val materializer = ActorMaterializer()
//
//  import system.dispatcher
//
//  val requestRendered = ByteString(
//    "GET / HTTP/1.1\r\n" +
//      "Accept: */*\r\n" +
//      "Accept-Encoding: gzip, deflate\r\n" +
//      "Connection: keep-alive\r\n" +
//      "Host: example.com\r\n" +
//      "User-Agent: HTTPie/0.9.3\r\n" +
//      "\r\n"
//  )
//
//  def TCPPlacebo(requests: Int): Flow[ByteString, ByteString, NotUsed] =
//    Flow.fromSinkAndSource(
//      Flow[ByteString].takeWhile(it => !(it.utf8String contains "Connection: close")) to Sink.ignore,
//      Source.repeat(requestRendered).take(requests)
//    )
//
//  def TlsPlacebo = TLSPlacebo()
//
//  val requestRendering: Flow[HttpRequest, String, NotUsed] =
//    Http()
//      .clientLayer(headers.Host("blah.com"))
//      .atop(TlsPlacebo)
//      .join {
//        Flow[ByteString].map { x ⇒
//          val response = s"HTTP/1.1 200 OK\r\nContent-Length: ${x.size}\r\n\r\n"
//          ByteString(response) ++ x
//        }
//      }
//      .mapAsync(1)(response => Unmarshal(response).to[String])
//
//  def renderResponse: Future[String] = Source.single(HttpRequest(uri = "/foo"))
//    .via(requestRendering)
//    .runWith(Sink.head)
//
//  var request: HttpRequest = _
//  var pool: Flow[(HttpRequest, Int), (Try[HttpResponse], Int), _] = _
//
//  val simpleResponse =
//    ResponseRenderingContext(
//      response = HttpResponse(
//        200,
//        headers = Nil,
//        entity = HttpEntity("ENTITY")
//      ),
//      requestMethod = HttpMethods.GET
//    )
//
//  val jsonResponse =
//    ResponseRenderingContext(
//      response = HttpResponse(
//        200,
//        headers = Nil,
//        entity = HttpEntity(ContentTypes.`application/json`, """{"message":"Hello, World!"}""")
//      ),
//      requestMethod = HttpMethods.GET
//    )
//
//  def main(args: Array[String]): Unit = {
//    implicit val system = ActorSystem("HttpResponseRenderingBenchmark", config)
//    implicit val materializer = ActorMaterializer()
//
//    println("------ simple response ---------")
//    renderToImpl(simpleResponse, null, 1, print = true)
//    println("--------------------------------")
//    println()
//    println("------ json response ---------")
//    renderToImpl(jsonResponse, null, 1, print = true)
//    println("--------------------------------")
//
//    system.terminate()
//  }
//
//  class JitSafeLatch[A](blackhole: Blackhole, n: Int) extends GraphStageWithMaterializedValue[SinkShape[A], CountDownLatch] {
//    val in = Inlet[A]("JitSafeLatch.in")
//    override val shape = SinkShape(in)
//
//    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, CountDownLatch) = {
//      val latch = new CountDownLatch(n)
//      val logic = new GraphStageLogic(shape) with InHandler {
//
//        override def preStart(): Unit = pull(in)
//        override def onPush(): Unit = {
//          val element = grab(in)
//          if (blackhole ne null) blackhole.consume(element)
//          latch.countDown()
//          pull(in)
//        }
//
//        setHandler(in, this)
//      }
//
//      (logic, latch)
//    }
//  }
//
//  def renderToImpl(ctx: ResponseRenderingContext, blackhole: Blackhole, n: Int, print: Boolean = false)(implicit mat: Materializer): CountDownLatch = {
//    val latch =
//      if (print)
//        Source.repeat(ctx).take(n) // never send upstream completion
//          .via(renderer.named("renderer"))
//          .map(out => {
//            println(out.asInstanceOf[HttpData].bytes.utf8String)
//            out
//          })
//          .runWith(new JitSafeLatch[ResponseRenderingOutput](blackhole, n))
//      else
//        (Source.repeat(ctx).take(n) ++ Source.maybe[ResponseRenderingContext]) // never send upstream completion
//          .via(renderer.named("renderer"))
//          .runWith(new JitSafeLatch[ResponseRenderingOutput](blackhole, n))
//
//    latch
//  }
//
//  override def currentTimeMillis(): Long = System.currentTimeMillis() // DateTime(2011, 8, 25, 9, 10, 29).clicks // provide a stable date for testing  
//
//}
