/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.net.Socket
import java.io.{ InputStreamReader, BufferedReader, OutputStreamWriter, BufferedWriter }
import com.typesafe.config.{ ConfigFactory, Config }
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.io.IO
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.stream.testkit.{ ProducerProbe, ConsumerProbe, StreamTestKit }
import akka.stream.impl.SynchronousProducerFromIterable
import akka.stream.scaladsl.Flow
import akka.http.server.ServerSettings
import akka.http.client.ClientConnectionSettings
import akka.http.model._
import akka.http.util._
import headers._
import HttpMethods._
import HttpEntity._
import TestUtils._

class ClientServerSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  val materializerSettings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  val materializer = FlowMaterializer(materializerSettings)

  "The server-side HTTP infrastructure" should {

    "properly bind and unbind a server" in {
      val (hostname, port) = temporaryServerHostnameAndPort()
      val commander = TestProbe()
      commander.send(IO(Http), Http.Bind(hostname, port, materializerSettings = materializerSettings))

      val Http.ServerBinding(localAddress, connectionStream) = commander.expectMsgType[Http.ServerBinding]
      localAddress.getHostName shouldEqual hostname
      localAddress.getPort shouldEqual port

      val c = StreamTestKit.consumerProbe[Http.IncomingConnection]
      connectionStream.produceTo(c)
      val sub = c.expectSubscription()

      sub.cancel()
      // TODO: verify unbinding effect
    }

    "properly complete a simple request/response cycle" in new TestSetup {
      val (clientOut, clientIn) = openNewClientConnection[Symbol]()
      val (serverIn, serverOut) = acceptConnection()

      val clientOutSub = clientOut.expectSubscription()
      clientOutSub.sendNext(HttpRequest(uri = "/abc") -> 'abcContext)

      val serverInSub = serverIn.expectSubscription()
      serverInSub.requestMore(1)
      serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

      val serverOutSub = serverOut.expectSubscription()
      serverOutSub.sendNext(HttpResponse(entity = "yeah"))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.requestMore(1)
      val (response, 'abcContext) = clientIn.expectNext()
      toStrict(response.entity) shouldEqual HttpEntity("yeah")
    }

    "properly complete a chunked request/response cycle" in new TestSetup {
      val (clientOut, clientIn) = openNewClientConnection[Long]()
      val (serverIn, serverOut) = acceptConnection()

      val chunks = List(Chunk("abc"), Chunk("defg"), Chunk("hijkl"), LastChunk)
      val chunkedContentType: ContentType = MediaTypes.`application/base64`
      val chunkedEntity = HttpEntity.Chunked(chunkedContentType, SynchronousProducerFromIterable(chunks))

      val clientOutSub = clientOut.expectSubscription()
      clientOutSub.sendNext(HttpRequest(POST, "/chunked", List(Accept(MediaRanges.`*/*`)), chunkedEntity) -> 12345678)

      val serverInSub = serverIn.expectSubscription()
      serverInSub.requestMore(1)
      private val HttpRequest(POST, uri, List(`User-Agent`(_), Host(_, _), Accept(Vector(MediaRanges.`*/*`))),
        Chunked(`chunkedContentType`, chunkStream), HttpProtocols.`HTTP/1.1`) = serverIn.expectNext()
      uri shouldEqual Uri(s"http://$hostname:$port/chunked")
      Await.result(Flow(chunkStream).grouped(4).toFuture(materializer), 100.millis) shouldEqual chunks

      val serverOutSub = serverOut.expectSubscription()
      serverOutSub.sendNext(HttpResponse(206, List(RawHeader("Age", "42")), chunkedEntity))

      val clientInSub = clientIn.expectSubscription()
      clientInSub.requestMore(1)
      val (HttpResponse(StatusCodes.PartialContent, List(Date(_), Server(_), RawHeader("Age", "42")),
        Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`), 12345678) = clientIn.expectNext()
      Await.result(Flow(chunkStream2).grouped(1000).toFuture(materializer), 100.millis) shouldEqual chunks
    }

  }

  override def afterAll() = system.shutdown()

  class TestSetup {
    val (hostname, port) = temporaryServerHostnameAndPort()
    val bindHandler = TestProbe()
    def configOverrides = ""

    // automatically bind a server
    val connectionStream: ConsumerProbe[Http.IncomingConnection] = {
      val commander = TestProbe()
      val settings = configOverrides.toOption.map(ServerSettings.apply)
      commander.send(IO(Http), Http.Bind(hostname, port, serverSettings = settings, materializerSettings = materializerSettings))
      val probe = StreamTestKit.consumerProbe[Http.IncomingConnection]
      commander.expectMsgType[Http.ServerBinding].connectionStream.produceTo(probe)
      probe
    }
    val connectionStreamSub = connectionStream.expectSubscription()

    def openNewClientConnection[T](settings: Option[ClientConnectionSettings] = None): (ProducerProbe[(HttpRequest, T)], ConsumerProbe[(HttpResponse, T)]) = {
      val commander = TestProbe()
      commander.send(IO(Http), Http.Connect(hostname, port, settings = settings, materializerSettings = materializerSettings))
      val connection = commander.expectMsgType[Http.OutgoingConnection]
      connection.remoteAddress.getPort shouldEqual port
      connection.remoteAddress.getHostName shouldEqual hostname
      val requestProducerProbe = StreamTestKit.producerProbe[(HttpRequest, T)]
      val responseConsumerProbe = StreamTestKit.consumerProbe[(HttpResponse, T)]
      requestProducerProbe.produceTo(connection.processor[T])
      connection.processor[T].produceTo(responseConsumerProbe)
      requestProducerProbe -> responseConsumerProbe
    }

    def acceptConnection(): (ConsumerProbe[HttpRequest], ProducerProbe[HttpResponse]) = {
      connectionStreamSub.requestMore(1)
      val Http.IncomingConnection(_, requestProducer, responseConsumer) = connectionStream.expectNext()
      val requestConsumerProbe = StreamTestKit.consumerProbe[HttpRequest]
      val responseProducerProbe = StreamTestKit.producerProbe[HttpResponse]
      requestProducer.produceTo(requestConsumerProbe)
      responseProducerProbe.produceTo(responseConsumer)
      requestConsumerProbe -> responseProducerProbe
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

  def toStrict(entity: HttpEntity): HttpEntity.Strict =
    Await.result(entity.toStrict(500.millis, materializer), 1.second)
}