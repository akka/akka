/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.Socket
import akka.http.scaladsl.Http.ServerBinding
import akka.http.{ ClientConnectionSettings, ServerSettings }
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import scala.annotation.tailrec
import scala.concurrent.{ Promise, Future, Await }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import akka.actor.ActorSystem
import akka.testkit.EventFilter
import akka.stream.{ StreamTcpException, ActorMaterializer, BindFailedException }
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._

import scala.util.{ Failure, Try, Success }

class ClientServerSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    akka.log-dead-letters = OFF
    akka.io.tcp.windows-connection-abort-workaround-enabled = auto
  """)
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  val testConf2: Config =
    ConfigFactory.parseString("akka.stream.materializer.subscription-timeout.timeout = 1 s")
      .withFallback(testConf)
  val system2 = ActorSystem(getClass.getSimpleName, testConf2)
  val materializer2 = ActorMaterializer.create(system2)

  "The low-level HTTP infrastructure" should {

    "properly bind a server" in {
      val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]()
      val binding = Http().bind(hostname, port).toMat(Sink(probe))(Keep.left).run()
      val sub = probe.expectSubscription() // if we get it we are bound
      val address = Await.result(binding, 1.second).localAddress
      sub.cancel()
    }

    "report failure if bind fails" in {
      val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = Http().bind(hostname, port)
      val probe1 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      // Bind succeeded, we have a local address
      val b1 = Await.result(binding.to(Sink(probe1)).run(), 3.seconds)
      probe1.expectSubscription()

      val probe2 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.to(Sink(probe2)).run(), 3.seconds) }
      probe2.expectSubscriptionAndError()

      val probe3 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.to(Sink(probe3)).run(), 3.seconds) }
      probe3.expectSubscriptionAndError()

      // Now unbind the first
      Await.result(b1.unbind(), 1.second)
      probe1.expectComplete()

      if (!akka.util.Helpers.isWindows) {
        val probe4 = TestSubscriber.manualProbe[Http.IncomingConnection]()
        // Bind succeeded, we have a local address
        val b2 = Await.result(binding.to(Sink(probe4)).run(), 3.seconds)
        probe4.expectSubscription()

        // clean up
        Await.result(b2.unbind(), 1.second)
      }
    }

    "run with bindAndHandleSync" in {
      val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = Http().bindAndHandleSync(_ ⇒ HttpResponse(), hostname, port)
      val b1 = Await.result(binding, 3.seconds)

      val (_, f) = Http().outgoingConnection(hostname, port)
        .runWith(Source.single(HttpRequest(uri = "/abc")), Sink.head)

      Await.result(f, 1.second)
      Await.result(b1.unbind(), 1.second)
    }

    "prevent more than the configured number of max-connections with bindAndHandle" in {
      val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
      val settings = ServerSettings(system).copy(maxConnections = 1)

      val receivedSlow = Promise[Long]()
      val receivedFast = Promise[Long]()

      def handle(req: HttpRequest): Future[HttpResponse] = {
        req.uri.path.toString match {
          case "/slow" ⇒
            receivedSlow.complete(Success(System.nanoTime()))
            akka.pattern.after(1.seconds, system.scheduler)(Future.successful(HttpResponse()))
          case "/fast" ⇒
            receivedFast.complete(Success(System.nanoTime()))
            Future.successful(HttpResponse())
        }
      }

      val binding = Http().bindAndHandleAsync(handle, hostname, port, settings = settings)
      val b1 = Await.result(binding, 3.seconds)

      def runRequest(uri: Uri): Unit =
        Http().outgoingConnection(hostname, port)
          .runWith(Source.single(HttpRequest(uri = uri)), Sink.head)

      runRequest("/slow")

      // wait until first request was received (but not yet answered)
      val slowTime = Await.result(receivedSlow.future, 2.second)

      // should be blocked by the slow connection still being open
      runRequest("/fast")

      val fastTime = Await.result(receivedFast.future, 2.second)
      val diff = fastTime - slowTime
      diff should be > 1000000000L // the diff must be at least the time to complete the first request and to close the first connection

      Await.result(b1.unbind(), 1.second)
    }

    "close connection with idle client after idleTimeout" in {
      val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
      val s = ServerSettings(system)
      val theIdleTimeout = 300.millis
      val settings = s.copy(timeouts = s.timeouts.copy(idleTimeout = theIdleTimeout))

      val receivedRequest = Promise[Long]()

      def handle(req: HttpRequest): Future[HttpResponse] = {
        receivedRequest.complete(Success(System.nanoTime()))
        Promise().future // never complete the request with a response; 're waiting for the timeout to happen, nothing else
      }

      val binding = Http().bindAndHandleAsync(handle, hostname, port, settings = settings)
      val b1 = Await.result(binding, 3.seconds)

      def runIdleRequest(uri: Uri): Future[HttpResponse] = {
        val itNeverEnds = Chunked.fromData(ContentTypes.`text/plain`, Source.maybe[ByteString])
        Http().outgoingConnection(hostname, port)
          .runWith(Source.single(HttpRequest(PUT, uri, entity = itNeverEnds)), Sink.head)
          ._2
      }

      val clientsResponseFuture = runIdleRequest("/")

      // await for the server to get the request
      val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds)

      // waiting for the timeout to happen on the client
      intercept[StreamTcpException] { Await.result(clientsResponseFuture, 2.second) }

      (System.nanoTime() - serverReceivedRequestAtNanos).millis should be >= theIdleTimeout
    }

    "log materialization errors in `bindAndHandle`" which {

      "are triggered in `transform`" in Utils.assertAllStagesStopped {
        // FIXME racy feature, needs https://github.com/akka/akka/issues/17849 to be fixed
        pending
        val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
        val flow = Flow[HttpRequest].transform[HttpResponse](() ⇒ sys.error("BOOM"))
        val binding = Http(system2).bindAndHandle(flow, hostname, port)(materializer2)
        val b1 = Await.result(binding, 3.seconds)

        EventFilter[RuntimeException](message = "BOOM", occurrences = 1).intercept {
          val (_, responseFuture) =
            Http(system2).outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.head)(materializer2)
          try Await.result(responseFuture, 5.second).status should ===(StatusCodes.InternalServerError)
          catch {
            case _: StreamTcpException ⇒
            // Also fine, depends on the race between abort and 500, caused by materialization panic which
            // tries to tear down everything, but the order is nondeterministic
          }
        }(system2)
        Await.result(b1.unbind(), 1.second)
      }(materializer2)

      "are triggered in `mapMaterialized`" in Utils.assertAllStagesStopped {
        // FIXME racy feature, needs https://github.com/akka/akka/issues/17849 to be fixed
        pending
        val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
        val flow = Flow[HttpRequest].map(_ ⇒ HttpResponse()).mapMaterializedValue(_ ⇒ sys.error("BOOM"))
        val binding = Http(system2).bindAndHandle(flow, hostname, port)(materializer2)
        val b1 = Await.result(binding, 1.seconds)

        EventFilter[RuntimeException](message = "BOOM", occurrences = 1).intercept {
          val (_, responseFuture) =
            Http(system2).outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.head)(materializer2)
          try Await.result(responseFuture, 5.seconds).status should ===(StatusCodes.InternalServerError)
          catch {
            case _: StreamTcpException ⇒
            // Also fine, depends on the race between abort and 500, caused by materialization panic which
            // tries to tear down everything, but the order is nondeterministic
          }
        }(system2)
        Await.result(b1.unbind(), 1.second)
      }(materializer2)
    }

    "properly complete a simple request/response cycle" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.expectRequest()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        clientOutSub.sendComplete()
        serverInSub.request(1) // work-around for #16552
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientInSub.request(1) // work-around for #16552
        clientIn.expectComplete()

        binding.foreach(_.unbind())
      }
    }

    "properly complete a chunked request/response cycle" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val chunks = List(Chunk("abc"), Chunk("defg"), Chunk("hijkl"), LastChunk)
        val chunkedContentType: ContentType = MediaTypes.`application/base64`
        val chunkedEntity = HttpEntity.Chunked(chunkedContentType, Source(chunks))

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.sendNext(HttpRequest(POST, "/chunked", List(Accept(MediaRanges.`*/*`)), chunkedEntity))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        private val HttpRequest(POST, uri, List(Accept(Seq(MediaRanges.`*/*`)), Host(_, _), `User-Agent`(_)),
          Chunked(`chunkedContentType`, chunkStream), HttpProtocols.`HTTP/1.1`) = serverIn.expectNext()
        uri shouldEqual Uri(s"http://$hostname:$port/chunked")
        Await.result(chunkStream.grouped(4).runWith(Sink.head), 100.millis) shouldEqual chunks

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(206, List(Age(42)), chunkedEntity))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val HttpResponse(StatusCodes.PartialContent, List(Age(42), Server(_), Date(_)),
          Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`) = clientIn.expectNext()
        Await.result(chunkStream2.grouped(1000).runWith(Sink.head), 100.millis) shouldEqual chunks

        clientOutSub.sendComplete()
        serverInSub.request(1) // work-around for #16552
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientInSub.request(1) // work-around for #16552
        clientIn.expectComplete()

        connSourceSub.cancel()
      }
    }

    "be able to deal with eager closing of the request stream on the client side" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))
        clientOutSub.sendComplete()
        // complete early

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        serverInSub.request(1) // work-around for #16552
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientInSub.request(1) // work-around for #16552
        clientIn.expectComplete()

        connSourceSub.cancel()
      }
    }
  }

  override def afterAll() = {
    system.shutdown()
    system2.shutdown()
  }

  class TestSetup {
    val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
    def configOverrides = ""

    // automatically bind a server
    val (connSource, binding: Future[ServerBinding]) = {
      val settings = configOverrides.toOption.fold(ServerSettings(system))(ServerSettings(_))
      val connections = Http().bind(hostname, port, settings = settings)
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]
      val binding = connections.toMat(Sink(probe))(Keep.left).run()
      (probe, binding)
    }
    val connSourceSub = connSource.expectSubscription()

    def openNewClientConnection(settings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val requestPublisherProbe = TestPublisher.manualProbe[HttpRequest]()
      val responseSubscriberProbe = TestSubscriber.manualProbe[HttpResponse]()

      val connectionFuture = Source(requestPublisherProbe)
        .viaMat(Http().outgoingConnection(hostname, port, settings = settings))(Keep.right)
        .to(Sink(responseSubscriberProbe)).run()

      val connection = Await.result(connectionFuture, 3.seconds)

      connection.remoteAddress.getHostName shouldEqual hostname
      connection.remoteAddress.getPort shouldEqual port
      requestPublisherProbe -> responseSubscriberProbe
    }

    def acceptConnection(): (TestSubscriber.ManualProbe[HttpRequest], TestPublisher.ManualProbe[HttpResponse]) = {
      connSourceSub.request(1)
      val incomingConnection = connSource.expectNext()
      val sink = Sink.publisher[HttpRequest]
      val source = Source.subscriber[HttpResponse]

      val handler = Flow.fromSinkAndSourceMat(sink, source)(Keep.both)

      val (pub, sub) = incomingConnection.handleWith(handler)
      val requestSubscriberProbe = TestSubscriber.manualProbe[HttpRequest]()
      val responsePublisherProbe = TestPublisher.manualProbe[HttpResponse]()

      pub.subscribe(requestSubscriberProbe)
      responsePublisherProbe.subscribe(sub)
      requestSubscriberProbe -> responsePublisherProbe
    }

    def openClientSocket() = new Socket(hostname, port)

    def write(socket: Socket, data: String) = {
      val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream))
      writer.write(data)
      writer.flush()
      writer
    }

    def readAll(socket: Socket)(reader: BufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream))): (String, BufferedReader) = {
      val sb = new java.lang.StringBuilder
      val cbuf = new Array[Char](256)
      @tailrec def drain(): (String, BufferedReader) = reader.read(cbuf) match {
        case -1 ⇒ sb.toString -> reader
        case n  ⇒ sb.append(cbuf, 0, n); drain()
      }
      drain()
    }
  }

  def toStrict(entity: HttpEntity): HttpEntity.Strict = Await.result(entity.toStrict(500.millis), 1.second)
}
