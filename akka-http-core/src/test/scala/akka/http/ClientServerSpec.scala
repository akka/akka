/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.Socket
import com.typesafe.config.{ Config, ConfigFactory }
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import akka.actor.ActorSystem
import akka.stream.scaladsl.StreamTcp
import akka.stream.BindFailedException
import akka.stream.FlowMaterializer
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ PublisherProbe, SubscriberProbe }
import akka.stream.scaladsl._
import akka.http.engine.client.ClientConnectionSettings
import akka.http.engine.server.ServerSettings
import akka.http.model._
import akka.http.util._
import headers._
import HttpEntity._
import HttpMethods._
import TestUtils._

class ClientServerSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  implicit val materializer = FlowMaterializer()

  "The server-side HTTP infrastructure" should {

    "properly bind a server" in {
      val (hostname, port) = temporaryServerHostnameAndPort()
      val binding = Http().bind(hostname, port)
      val probe = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      binding.connections.runWith(Sink(probe))
      val sub = probe.expectSubscription() // if we get it we are bound
      sub.cancel()
    }

    "report failure if bind fails" in pendingUntilFixed { // FIXME: "unpend"!
      val (hostname, port) = temporaryServerHostnameAndPort()
      val binding = Http().bind(hostname, port)
      val probe1 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      val mm1 = binding.connections.to(Sink(probe1)).run()
      probe1.expectSubscription()

      val probe2 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      binding.connections.runWith(Sink(probe2))
      probe2.expectError(BindFailedException)

      Await.result(binding.unbind(mm1), 1.second)
      val probe3 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      val mm3 = binding.connections.to(Sink(probe3)).run()
      probe3.expectSubscription() // we bound a second time, which means the previous unbind was successful
      Await.result(binding.unbind(mm3), 1.second)
    }

    "properly complete a simple request/response cycle" in new TestSetup {
      val (clientOut, clientIn) = openNewClientConnection()
      val (serverIn, serverOut) = acceptConnection()

      val clientOutSub = clientOut.expectSubscription()
      clientOutSub.sendNext(HttpRequest(uri = "/abc"))

      val serverInSub = serverIn.expectSubscription()
      serverInSub.request(1)
      serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

      val serverOutSub = serverOut.expectSubscription()
      serverOutSub.sendNext(HttpResponse(entity = "yeah"))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.request(1)
      val response = clientIn.expectNext()
      toStrict(response.entity) shouldEqual HttpEntity("yeah")
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
      serverOutSub.sendNext(HttpResponse(206, List(RawHeader("Age", "42")), chunkedEntity))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.request(1)
      val HttpResponse(StatusCodes.PartialContent, List(RawHeader("Age", "42"), Server(_), Date(_)),
        Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`) = clientIn.expectNext()
      Await.result(chunkStream2.grouped(1000).runWith(Sink.head), 100.millis) shouldEqual chunks
    }

  }

  override def afterAll() = system.shutdown()

  class TestSetup {
    val (hostname, port) = temporaryServerHostnameAndPort()
    def configOverrides = ""

    // automatically bind a server
    val connSource = {
      val settings = configOverrides.toOption.map(ServerSettings.apply)
      val binding = Http().bind(hostname, port, settings = settings)
      val probe = StreamTestKit.SubscriberProbe[Http.IncomingConnection]
      binding.connections.runWith(Sink(probe))
      probe
    }
    val connSourceSub = connSource.expectSubscription()

    def openNewClientConnection(settings: Option[ClientConnectionSettings] = None): (PublisherProbe[HttpRequest], SubscriberProbe[HttpResponse]) = {
      val requestPublisherProbe = StreamTestKit.PublisherProbe[HttpRequest]()
      val responseSubscriberProbe = StreamTestKit.SubscriberProbe[HttpResponse]()
      val connection = Http().outgoingConnection(hostname, port, settings = settings)
      connection.remoteAddress.getHostName shouldEqual hostname
      connection.remoteAddress.getPort shouldEqual port
      Source(requestPublisherProbe).via(connection.flow).runWith(Sink(responseSubscriberProbe))
      requestPublisherProbe -> responseSubscriberProbe
    }

    def acceptConnection(): (SubscriberProbe[HttpRequest], PublisherProbe[HttpResponse]) = {
      connSourceSub.request(1)
      val incomingConnection = connSource.expectNext()
      val sink = PublisherSink[HttpRequest]()
      val source = SubscriberSource[HttpResponse]()
      val mm = incomingConnection.handleWith(Flow(sink, source))
      val requestSubscriberProbe = StreamTestKit.SubscriberProbe[HttpRequest]()
      val responsePublisherProbe = StreamTestKit.PublisherProbe[HttpResponse]()
      mm.get(sink).subscribe(requestSubscriberProbe)
      responsePublisherProbe.subscribe(mm.get(source))
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
