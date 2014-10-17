/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.Socket
import com.typesafe.config.{ Config, ConfigFactory }
import org.reactivestreams.Publisher
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import akka.actor.{ Status, ActorSystem }
import akka.io.IO
import akka.testkit.TestProbe
import akka.stream.impl.SynchronousPublisherFromIterable
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ PublisherProbe, SubscriberProbe }
import akka.stream.scaladsl2._
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
      val commander = TestProbe()
      commander.send(IO(Http), Http.Bind(hostname, port))

      val Http.ServerBinding(localAddress, connectionStream) = commander.expectMsgType[Http.ServerBinding]
      localAddress.getHostName shouldEqual hostname
      localAddress.getPort shouldEqual port

      val c = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      connectionStream.subscribe(c)
      val sub = c.expectSubscription()

      sub.cancel()
      // TODO: verify unbinding effect
    }

    "report failure if bind fails" in {
      val (hostname, port) = temporaryServerHostnameAndPort()
      val commander = TestProbe()
      commander.send(IO(Http), Http.Bind(hostname, port))
      val binding = commander.expectMsgType[Http.ServerBinding]
      commander.send(IO(Http), Http.Bind(hostname, port))
      commander.expectMsgType[Status.Failure]

      // Clean up
      val c = StreamTestKit.SubscriberProbe[Http.IncomingConnection]()
      binding.connectionStream.subscribe(c)
      val sub = c.expectSubscription()
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
      Await.result(chunkStream.grouped(4).runWith(Sink.future), 100.millis) shouldEqual chunks

      val serverOutSub = serverOut.expectSubscription()
      serverOutSub.sendNext(HttpResponse(206, List(RawHeader("Age", "42")), chunkedEntity))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.request(1)
      val (HttpResponse(StatusCodes.PartialContent, List(Date(_), Server(_), RawHeader("Age", "42")),
        Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`), 12345678) = clientIn.expectNext()
      Await.result(chunkStream2.grouped(1000).runWith(Sink.future), 100.millis) shouldEqual chunks
    }

  }

  override def afterAll() = system.shutdown()

  class TestSetup {
    val (hostname, port) = temporaryServerHostnameAndPort()
    val bindHandler = TestProbe()
    def configOverrides = ""

    // automatically bind a server
    val connectionStream: SubscriberProbe[Http.IncomingConnection] = {
      val commander = TestProbe()
      val settings = configOverrides.toOption.map(ServerSettings.apply)
      commander.send(IO(Http), Http.Bind(hostname, port, serverSettings = settings))
      val probe = StreamTestKit.SubscriberProbe[Http.IncomingConnection]
      commander.expectMsgType[Http.ServerBinding].connectionStream.subscribe(probe)
      probe
    }
    val connectionStreamSub = connectionStream.expectSubscription()

    def openNewClientConnection[T](settings: Option[ClientConnectionSettings] = None): (PublisherProbe[(HttpRequest, T)], SubscriberProbe[(HttpResponse, T)]) = {
      val commander = TestProbe()
      commander.send(IO(Http), Http.Connect(hostname, port, settings = settings))
      val connection = commander.expectMsgType[Http.OutgoingConnection]
      connection.remoteAddress.getPort shouldEqual port
      connection.remoteAddress.getHostName shouldEqual hostname
      val requestPublisherProbe = StreamTestKit.PublisherProbe[(HttpRequest, T)]()
      val responseSubscriberProbe = StreamTestKit.SubscriberProbe[(HttpResponse, T)]()
      requestPublisherProbe.subscribe(connection.requestSubscriber)
      connection.responsePublisher.asInstanceOf[Publisher[(HttpResponse, T)]].subscribe(responseSubscriberProbe)
      requestPublisherProbe -> responseSubscriberProbe
    }

    def acceptConnection(): (SubscriberProbe[HttpRequest], PublisherProbe[HttpResponse]) = {
      connectionStreamSub.request(1)
      val Http.IncomingConnection(_, requestPublisher, responseSubscriber) = connectionStream.expectNext()
      val requestSubscriberProbe = StreamTestKit.SubscriberProbe[HttpRequest]()
      val responsePublisherProbe = StreamTestKit.PublisherProbe[HttpResponse]()
      requestPublisher.subscribe(requestSubscriberProbe)
      responsePublisherProbe.subscribe(responseSubscriber)
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