/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.Socket
import akka.testkit.EventFilter
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.http.TestUtils._
import akka.http.engine.client.ClientConnectionSettings
import akka.http.engine.server.ServerSettings
import akka.http.model.HttpEntity._
import akka.http.model.HttpMethods._
import akka.http.model._
import akka.http.model.headers._
import akka.http.util._
import akka.stream.{ ActorFlowMaterializer, BindFailedException }
import akka.stream.scaladsl._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ PublisherProbe, SubscriberProbe }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

class ClientServerSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    akka.log-dead-letters = OFF""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  implicit val materializer = ActorFlowMaterializer()

  "The low-level HTTP infrastructure" should {

    "properly bind a server" in {
      val (_, hostname, port) = temporaryServerHostnameAndPort()
      val probe = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      val binding = Http().bind(hostname, port).toMat(Sink(probe))(Keep.left).run()
      val sub = probe.expectSubscription() // if we get it we are bound
      val address = Await.result(binding, 1.second).localAddress
      sub.cancel()
    }

    "report failure if bind fails" in {
      val (_, hostname, port) = temporaryServerHostnameAndPort()
      val binding = Http().bind(hostname, port)
      val probe1 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      // Bind succeeded, we have a local address
      val b1 = Await.result(binding.to(Sink(probe1)).run(), 3.seconds)
      probe1.expectSubscription()

      val probe2 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.to(Sink(probe2)).run(), 3.seconds) }
      probe2.expectSubscriptionAndError()

      val probe3 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy { Await.result(binding.to(Sink(probe3)).run(), 3.seconds) }
      probe3.expectSubscriptionAndError()

      // Now unbind the first
      Await.result(b1.unbind(), 1.second)
      probe1.expectComplete()

      if (!akka.util.Helpers.isWindows) {
        val probe4 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
        // Bind succeeded, we have a local address
        val b2 = Await.result(binding.to(Sink(probe4)).run(), 3.seconds)
        probe4.expectSubscription()

        // clean up
        Await.result(b2.unbind(), 1.second)
      }
    }

    "run with bindAndHandleSync" in {
      val (_, hostname, port) = temporaryServerHostnameAndPort()
      val binding = Http().bindAndHandleSync(_ ⇒ HttpResponse(), hostname, port)
      val b1 = Await.result(binding, 3.seconds)

      val (_, f) = Http().outgoingConnection(hostname, port)
        .runWith(Source.single(HttpRequest(uri = "/abc")), Sink.head)

      Await.result(f, 1.second)
      Await.result(b1.unbind(), 1.second)
    }

    "log materialization errors in `bindAndHandle`" which {
      "are triggered in `transform`" in {
        val (_, hostname, port) = temporaryServerHostnameAndPort()
        val flow = Flow[HttpRequest].transform[HttpResponse](() ⇒ sys.error("BOOM"))
        val binding = Http().bindAndHandle(flow, hostname, port)
        val b1 = Await.result(binding, 3.seconds)

        EventFilter[RuntimeException](message = "BOOM", occurrences = 1) intercept {
          val (_, responseFuture) = Http().outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.head)
          Await.result(responseFuture.failed, 1.second) shouldBe a[NoSuchElementException]
        }
        Await.result(b1.unbind(), 1.second)
      }
      "are triggered in `mapMaterialized`" in {
        val (_, hostname, port) = temporaryServerHostnameAndPort()
        val flow = Flow[HttpRequest].map(_ ⇒ HttpResponse()).mapMaterialized(_ ⇒ sys.error("BOOM"))
        val binding = Http().bindAndHandle(flow, hostname, port)
        val b1 = Await.result(binding, 3.seconds)

        EventFilter[RuntimeException](message = "BOOM", occurrences = 1) intercept {
          val (_, responseFuture) = Http().outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.head)
          Await.result(responseFuture.failed, 1.second) shouldBe a[NoSuchElementException]
        }
        Await.result(b1.unbind(), 1.second)
      }
    }

    "properly complete a simple request/response cycle" in new TestSetup {
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
    }

    "properly complete a chunked request/response cycle" in new TestSetup {
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
    }

    "be able to deal with eager closing of the request stream on the client side" in new TestSetup {
      val (clientOut, clientIn) = openNewClientConnection()
      val (serverIn, serverOut) = acceptConnection()

      val clientOutSub = clientOut.expectSubscription()
      clientOutSub.sendNext(HttpRequest(uri = "/abc"))
      clientOutSub.sendComplete() // complete early

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
    }
  }

  override def afterAll() = system.shutdown()

  class TestSetup {
    val (_, hostname, port) = temporaryServerHostnameAndPort()
    def configOverrides = ""

    // automatically bind a server
    val connSource = {
      val settings = configOverrides.toOption.fold(ServerSettings(system))(ServerSettings(_))
      val binding = Http().bind(hostname, port, settings = settings)
      val probe = StreamTestKit.SubscriberProbe[Http.IncomingConnection]
      binding.runWith(Sink(probe))
      probe
    }
    val connSourceSub = connSource.expectSubscription()

    def openNewClientConnection(settings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val requestPublisherProbe = StreamTestKit.PublisherProbe[HttpRequest]()
      val responseSubscriberProbe = StreamTestKit.SubscriberProbe[HttpResponse]()

      val connectionFuture = Source(requestPublisherProbe)
        .viaMat(Http().outgoingConnection(hostname, port, settings = settings))(Keep.right)
        .to(Sink(responseSubscriberProbe)).run()

      val connection = Await.result(connectionFuture, 3.seconds)

      connection.remoteAddress.getHostName shouldEqual hostname
      connection.remoteAddress.getPort shouldEqual port
      requestPublisherProbe -> responseSubscriberProbe
    }

    def acceptConnection(): (SubscriberProbe[HttpRequest], PublisherProbe[HttpResponse]) = {
      connSourceSub.request(1)
      val incomingConnection = connSource.expectNext()
      val sink = Sink.publisher[HttpRequest]
      val source = Source.subscriber[HttpResponse]

      val handler = Flow(sink, source)(Keep.both) { implicit b ⇒
        (snk, src) ⇒
          (snk.inlet, src.outlet)
      }

      val (pub, sub) = incomingConnection.handleWith(handler)
      val requestSubscriberProbe = StreamTestKit.SubscriberProbe[HttpRequest]()
      val responsePublisherProbe = StreamTestKit.PublisherProbe[HttpResponse]()

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
