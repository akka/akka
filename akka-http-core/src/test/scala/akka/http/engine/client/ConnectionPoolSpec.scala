/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import java.util.concurrent.atomic.AtomicInteger
import akka.http.engine.client.PoolGateway

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import akka.http.engine.server.ServerSettings
import akka.http.util.{ SingletonException, StreamUtils }
import akka.util.ByteString
import akka.stream.{ BidiShape, ActorFlowMaterializer }
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.scaladsl._
import akka.http.{ Http, TestUtils }
import akka.http.model.headers._
import akka.http.model._

class ConnectionPoolSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF\n akka.io.tcp.trace-logging = off") {
  implicit val materializer = ActorFlowMaterializer()

  "The host-level client infrastructure" should {

    "properly complete a simple request/response cycle" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/") -> 42)

      acceptIncomingConnection()
      responseOutSub.request(1)
      val (Success(response), 42) = responseOut.expectNext()
      response.headers should contain(RawHeader("Req-Host", s"localhost:$serverPort"))
    }

    "open a second connection if the first one is loaded" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)

      responseOutSub.request(2)
      acceptIncomingConnection()
      val r1 = responseOut.expectNext()
      acceptIncomingConnection()
      val r2 = responseOut.expectNext()

      Seq(r1, r2) foreach {
        case (Success(x), 42) ⇒ requestUri(x) should endWith("/a")
        case (Success(x), 43) ⇒ requestUri(x) should endWith("/b")
        case x                ⇒ fail(x.toString)
      }
      Seq(r1, r2).map(t ⇒ connNr(t._1.get)) should contain allOf (1, 2)
    }

    "not open a second connection if there is an idle one available" in new TestSetup {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      acceptIncomingConnection()
      responseOutSub.request(1)
      val (Success(response1), 42) = responseOut.expectNext()
      connNr(response1) shouldEqual 1

      requestIn.sendNext(HttpRequest(uri = "/b") -> 43)
      responseOutSub.request(1)
      val (Success(response2), 43) = responseOut.expectNext()
      connNr(response2) shouldEqual 1
    }

    "be able to handle 500 pipelined requests against the test server" in new TestSetup {
      val settings = ConnectionPoolSettings(system).copy(maxConnections = 4, pipeliningLimit = 2)
      val poolFlow = Http().cachedHostConnectionPool[Int](serverHostName, serverPort, settings = settings)

      val N = 500
      val requestIds = Source(() ⇒ Iterator.from(1)).take(N)
      val idSum = requestIds.map(id ⇒ HttpRequest(uri = s"/r$id") -> id).via(poolFlow).map {
        case (Success(response), id) ⇒
          requestUri(response) should endWith(s"/r$id")
          id
        case x ⇒ fail(x.toString)
      }.runFold(0)(_ + _)

      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()
      acceptIncomingConnection()

      Await.result(idSum, 10.seconds) shouldEqual N * (N + 1) / 2
    }

    "properly surface connection-level errors" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](maxRetries = 0)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash")) sys.error("CRASH BOOM BANG") else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) ⇒ x.getMessage should include("reset by peer") }
    }

    "retry failed requests" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int]()

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      val remainingResponsesToKill = new AtomicInteger(1)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Success(x), 43) ⇒ requestUri(x) should endWith("/crash") }
    }

    "respect the configured `maxRetries` value" in new TestSetup(autoAccept = true) {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](maxRetries = 4)

      requestIn.sendNext(HttpRequest(uri = "/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = "/crash") -> 43)
      responseOutSub.request(2)

      val remainingResponsesToKill = new AtomicInteger(5)
      override def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString =
        if (bytes.utf8String.contains("/crash") && remainingResponsesToKill.decrementAndGet() >= 0)
          sys.error("CRASH BOOM BANG")
        else bytes

      val responses = Seq(responseOut.expectNext(), responseOut.expectNext())

      responses mustContainLike { case (Success(x), 42) ⇒ requestUri(x) should endWith("/a") }
      responses mustContainLike { case (Failure(x), 43) ⇒ x.getMessage should include("reset by peer") }
      remainingResponsesToKill.get() shouldEqual 0
    }

    "automatically shutdown after configured timeout periods" in new TestSetup() {
      val (_, _, _, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)
      val gateway = Await.result(hcp.gatewayFuture, 500.millis)
      val PoolGateway.Running(_, shutdownStartedPromise, shutdownCompletedPromise) = gateway.currentState
      shutdownStartedPromise.isCompleted shouldEqual false
      shutdownCompletedPromise.isCompleted shouldEqual false
      Await.result(shutdownStartedPromise.future, 1500.millis) shouldEqual () // verify shutdown start (after idle)
      Await.result(shutdownCompletedPromise.future, 1500.millis) shouldEqual () // verify shutdown completed
    }

    "transparently restart after idle shutdown" in new TestSetup() {
      val (requestIn, responseOut, responseOutSub, hcp) = cachedHostConnectionPool[Int](idleTimeout = 1.second)

      val gateway = Await.result(hcp.gatewayFuture, 500.millis)
      val PoolGateway.Running(_, _, shutdownCompletedPromise) = gateway.currentState
      Await.result(shutdownCompletedPromise.future, 1500.millis) shouldEqual () // verify shutdown completed

      requestIn.sendNext(HttpRequest(uri = "/") -> 42)

      acceptIncomingConnection()
      responseOutSub.request(1)
      val (Success(_), 42) = responseOut.expectNext()
    }
  }

  "The single-request client infrastructure" should {
    class LocalTestSetup extends TestSetup(ServerSettings(system).copy(rawRequestUriHeader = true), autoAccept = true)

    "properly complete a simple request/response cycle with a host header request" in new LocalTestSetup {
      val request = HttpRequest(uri = "/abc", headers = List(Host(serverHostName, serverPort)))
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Uri", s"http://localhost:$serverPort/abc"))
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/abc"))
    }

    "transform absolute request URIs into relative URIs plus host header" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort/abc?query#fragment")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/abc?query"))
      responseHeaders should contain(RawHeader("Req-Host", s"localhost:$serverPort"))
    }

    "support absolute request URIs without path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "/"))
    }

    "support absolute request URIs with a double slash path component" in new LocalTestSetup {
      val request = HttpRequest(uri = s"http://$serverHostName:$serverPort//foo")
      val responseFuture = Http().singleRequest(request)
      val responseHeaders = Await.result(responseFuture, 1.second).headers
      responseHeaders should contain(RawHeader("Req-Uri", s"http://localhost:$serverPort//foo"))
      responseHeaders should contain(RawHeader("Req-Raw-Request-URI", "//foo"))
    }

    "produce an error if the request does not contain a Host-header or an absolute URI" in {
      val request = HttpRequest(uri = "/foo")
      val responseFuture = Http().singleRequest(request)
      val thrown = the[IllegalUriException] thrownBy Await.result(responseFuture, 1.second)
      thrown should have message "Cannot establish effective URI of request to `/foo`, request has a relative URI and is missing a `Host` header"
    }
  }

  "The superPool client infrastructure" should {

    "route incoming requests to the right cached host connection pool" in new TestSetup(autoAccept = true) {
      val (serverEndpoint2, serverHostName2, serverPort2) = TestUtils.temporaryServerHostnameAndPort()
      Http().bindAndHandleSync(testServerHandler(0), serverHostName2, serverPort2)

      val (requestIn, responseOut, responseOutSub, hcp) = superPool[Int]()

      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName:$serverPort/a") -> 42)
      requestIn.sendNext(HttpRequest(uri = s"http://$serverHostName2:$serverPort2/b") -> 43)

      responseOutSub.request(2)
      Seq(responseOut.expectNext(), responseOut.expectNext()) foreach {
        case (Success(x), 42) ⇒ requestUri(x) shouldEqual s"http://$serverHostName:$serverPort/a"
        case (Success(x), 43) ⇒ requestUri(x) shouldEqual s"http://$serverHostName2:$serverPort2/b"
        case x                ⇒ fail(x.toString)
      }
    }
  }

  class TestSetup(serverSettings: ServerSettings = ServerSettings(system),
                  autoAccept: Boolean = false) {
    val (serverEndpoint, serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

    def testServerHandler(connNr: Int): HttpRequest ⇒ HttpResponse = {
      case HttpRequest(_, uri, headers, entity, _) ⇒
        val responseHeaders =
          ConnNrHeader(connNr) +:
            RawHeader("Req-Uri", uri.toString) +: headers.map(h ⇒ RawHeader("Req-" + h.name, h.value))
        HttpResponse(headers = responseHeaders, entity = entity)
    }

    def mapServerSideOutboundRawBytes(bytes: ByteString): ByteString = bytes

    val incomingConnectionCounter = new AtomicInteger
    val incomingConnections = StreamTestKit.SubscriberProbe[Http.IncomingConnection]
    val incomingConnectionsSub = {
      val rawBytesInjection = BidiFlow() { b ⇒
        val top = b.add(Flow[ByteString].map(mapServerSideOutboundRawBytes)
          .transform(StreamUtils.recover { case NoErrorComplete ⇒ ByteString.empty }))
        val bottom = b.add(Flow[ByteString])
        BidiShape(top.inlet, top.outlet, bottom.inlet, bottom.outlet)
      }
      val sink = if (autoAccept) Sink.foreach[Http.IncomingConnection](handleConnection) else Sink(incomingConnections)
      StreamTcp().bind(serverEndpoint, idleTimeout = serverSettings.timeouts.idleTimeout)
        .map { c ⇒
          val layer = Http().serverLayer(serverSettings, log)
          Http.IncomingConnection(c.localAddress, c.remoteAddress, layer atop rawBytesInjection join c.flow)
        }.runWith(sink)
      if (autoAccept) null else incomingConnections.expectSubscription()
    }

    def acceptIncomingConnection(): Unit = {
      incomingConnectionsSub.request(1)
      val conn = incomingConnections.expectNext()
      handleConnection(conn)
    }

    private def handleConnection(c: Http.IncomingConnection) =
      c.handleWithSyncHandler(testServerHandler(incomingConnectionCounter.incrementAndGet()))

    def cachedHostConnectionPool[T](maxConnections: Int = 2,
                                    maxRetries: Int = 2,
                                    maxOpenRequests: Int = 8,
                                    pipeliningLimit: Int = 1,
                                    idleTimeout: Duration = 5.seconds,
                                    ccSettings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val settings = ConnectionPoolSettings(maxConnections, maxRetries, maxOpenRequests, pipeliningLimit,
        idleTimeout, ClientConnectionSettings(system))
      flowTestBench(Http().cachedHostConnectionPool[T](serverHostName, serverPort, Nil, settings))
    }

    def superPool[T](maxConnections: Int = 2,
                     maxRetries: Int = 2,
                     maxOpenRequests: Int = 8,
                     pipeliningLimit: Int = 1,
                     idleTimeout: Duration = 5.seconds,
                     ccSettings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val settings = ConnectionPoolSettings(maxConnections, maxRetries, maxOpenRequests, pipeliningLimit,
        idleTimeout, ClientConnectionSettings(system))
      flowTestBench(Http().superPool[T](Nil, settings))
    }

    def flowTestBench[T, Mat](poolFlow: Flow[(HttpRequest, T), (Try[HttpResponse], T), Mat]) = {
      val requestIn = StreamTestKit.PublisherProbe[(HttpRequest, T)]
      val responseOut = StreamTestKit.SubscriberProbe[(Try[HttpResponse], T)]
      val hcp = Source(requestIn).viaMat(poolFlow)(Keep.right).toMat(Sink(responseOut))(Keep.left).run()
      val responseOutSub = responseOut.expectSubscription()
      (new StreamTestKit.AutoPublisher(requestIn), responseOut, responseOutSub, hcp)
    }

    def connNr(r: HttpResponse): Int = r.headers.find(_ is "conn-nr").get.value.toInt
    def requestUri(r: HttpResponse): String = r.headers.find(_ is "req-uri").get.value
  }

  case class ConnNrHeader(nr: Int) extends CustomHeader {
    def name = "Conn-Nr"
    def value = nr.toString
  }

  implicit class MustContain[T](specimen: Seq[T]) {
    def mustContainLike(pf: PartialFunction[T, Unit]): Unit =
      specimen.collectFirst(pf) getOrElse fail("did not contain")
  }

  object NoErrorComplete extends SingletonException
}