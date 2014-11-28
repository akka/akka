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

    "properly bind and unbind a server" in {
      val (hostname, port) = temporaryServerHostnameAndPort()
      val Http.ServerSource(source, key) = Http(system).bind(hostname, port)
      val c = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      val mm = source.to(Sink(c)).run()
      val Http.ServerBinding(localAddress) = Await.result(mm.get(key), 3.seconds)
      val sub = c.expectSubscription()
      localAddress.getHostName shouldEqual hostname
      localAddress.getPort shouldEqual port

      sub.cancel()

      // TODO: verify unbinding effect
    }

    "report failure if bind fails" in {
      val (hostname, port) = temporaryServerHostnameAndPort()
      val Http.ServerSource(source, key) = Http(system).bind(hostname, port)
      val c1 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      val mm1 = source.to(Sink(c1)).run()
      val sub = c1.expectSubscription()
      val Http.ServerBinding(localAddress) = Await.result(mm1.get(key), 3.seconds)
      localAddress.getHostName shouldEqual hostname
      localAddress.getPort shouldEqual port
      val c2 = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      val mm2 = source.to(Sink(c2)).run()
      val failure = intercept[akka.stream.io.StreamTcp.IncomingTcpException] {
        val serverBinding = Await.result(mm2.get(key), 3.seconds)
      }
      failure.getMessage should be("Bind failed")
      sub.cancel()
    }

    "properly complete a simple request/response cycle" in new TestSetup {
      val (clientOut, clientIn) = openNewClientConnection[Symbol]()
      val (serverIn, serverOut) = acceptConnection()

      val clientOutSub = clientOut.expectSubscription()
      clientOutSub.sendNext(HttpRequest(uri = "/abc") -> 'abcContext)

      val serverInSub = serverIn.expectSubscription()
      serverInSub.request(1)
      serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

      val serverOutSub = serverOut.expectSubscription()
      serverOutSub.sendNext(HttpResponse(entity = "yeah"))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.request(1)
      val (response, 'abcContext) = clientIn.expectNext()
      toStrict(response.entity) shouldEqual HttpEntity("yeah")
    }

    "properly complete a chunked request/response cycle" in new TestSetup {
      val (clientOut, clientIn) = openNewClientConnection[Long]()
      val (serverIn, serverOut) = acceptConnection()

      val chunks = List(Chunk("abc"), Chunk("defg"), Chunk("hijkl"), LastChunk)
      val chunkedContentType: ContentType = MediaTypes.`application/base64`
      val chunkedEntity = HttpEntity.Chunked(chunkedContentType, Source(chunks))

      val clientOutSub = clientOut.expectSubscription()
      clientOutSub.sendNext(HttpRequest(POST, "/chunked", List(Accept(MediaRanges.`*/*`)), chunkedEntity) -> 12345678)

      val serverInSub = serverIn.expectSubscription()
      serverInSub.request(1)
      private val HttpRequest(POST, uri, List(`User-Agent`(_), Host(_, _), Accept(Vector(MediaRanges.`*/*`))),
        Chunked(`chunkedContentType`, chunkStream), HttpProtocols.`HTTP/1.1`) = serverIn.expectNext()
      uri shouldEqual Uri(s"http://$hostname:$port/chunked")
      Await.result(chunkStream.grouped(4).runWith(Sink.head), 100.millis) shouldEqual chunks

      val serverOutSub = serverOut.expectSubscription()
      serverOutSub.sendNext(HttpResponse(206, List(RawHeader("Age", "42")), chunkedEntity))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.request(1)
      val (HttpResponse(StatusCodes.PartialContent, List(Date(_), Server(_), RawHeader("Age", "42")),
        Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`), 12345678) = clientIn.expectNext()
      Await.result(chunkStream2.grouped(1000).runWith(Sink.head), 100.millis) shouldEqual chunks
    }

  }

  override def afterAll() = system.shutdown()

  class TestSetup {
    val (hostname, port) = temporaryServerHostnameAndPort()
    def configOverrides = ""

    // automatically bind a server
    val connectionStream: SubscriberProbe[Http.IncomingConnection] = {
      val settings = configOverrides.toOption.map(ServerSettings.apply)
      val Http.ServerSource(source, key) = Http(system).bind(hostname, port, serverSettings = settings)
      val probe = StreamTestKit.SubscriberProbe[Http.IncomingConnection]
      source.to(Sink(probe)).run()
      probe
    }
    val connectionStreamSub = connectionStream.expectSubscription()

    def openNewClientConnection[T](settings: Option[ClientConnectionSettings] = None): (PublisherProbe[(HttpRequest, T)], SubscriberProbe[(HttpResponse, T)]) = {
      val outgoingFlow = Http(system).connect(hostname, port, settings = settings)
      val requestPublisherProbe = StreamTestKit.PublisherProbe[(HttpRequest, T)]()
      val responseSubscriberProbe = StreamTestKit.SubscriberProbe[(HttpResponse, T)]()
      val tflow = outgoingFlow.flow.asInstanceOf[Flow[((HttpRequest, T)), ((HttpResponse, T))]]
      val mm = Flow(Sink(responseSubscriberProbe), Source(requestPublisherProbe)).join(tflow).run()
      val connection = Await.result(mm.get(outgoingFlow.key), 3.seconds)
      connection.remoteAddress.getPort shouldEqual port
      connection.remoteAddress.getHostName shouldEqual hostname

      requestPublisherProbe -> responseSubscriberProbe
    }

    def acceptConnection(): (SubscriberProbe[HttpRequest], PublisherProbe[HttpResponse]) = {
      connectionStreamSub.request(1)
      val Http.IncomingConnection(remoteAddress, flow) = connectionStream.expectNext()
      val requestSubscriberProbe = StreamTestKit.SubscriberProbe[HttpRequest]()
      val responsePublisherProbe = StreamTestKit.PublisherProbe[HttpResponse]()
      Flow(Sink(requestSubscriberProbe), Source(responsePublisherProbe)).join(flow).run()

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
