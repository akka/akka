/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.{ CompletableFuture, CompletionStage, TimeUnit }

import akka.NotUsed
import akka.http.javadsl.ConnectHttp._
import akka.http.javadsl.model.ws._
import akka.http.javadsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.japi.Pair
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.javadsl.model._
import akka.http.scaladsl.TestUtils
import akka.japi.Function
import akka.stream.ActorMaterializer
import akka.stream.javadsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.TestSubscriber
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.util.Try

class HttpExtensionApiSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  // tries to cover all surface area of javadsl.Http

  type Host = String
  type Port = Int

  implicit val system = {
    val testConf = ConfigFactory.parseString("""
    akka.loggers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.stdout-loglevel = ERROR
    windows-connection-abort-workaround-enabled = auto
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = infinite""")

    ActorSystem(getClass.getSimpleName, testConf)
  }
  implicit val materializer = ActorMaterializer()

  val http = Http.get(system)
  val connectionContext = ConnectionContext.noEncryption()
  val serverSettings = ServerSettings.create(system)
  val poolSettings = ConnectionPoolSettings.create(system)
  val loggingAdapter = NoLogging

  val successResponse = HttpResponse.create().withStatus(200)

  val httpSuccessFunction = new Function[HttpRequest, HttpResponse] {
    @throws(classOf[Exception])
    override def apply(param: HttpRequest): HttpResponse = successResponse
  }

  val asyncHttpSuccessFunction = new Function[HttpRequest, CompletionStage[HttpResponse]] {
    @throws(classOf[Exception])
    override def apply(param: HttpRequest): CompletionStage[HttpResponse] =
      CompletableFuture.completedFuture(successResponse)
  }

  "The Java HTTP extension" should {

    // all four bind method overloads
    "properly bind a server (with three parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(toHost(host, port), materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    "properly bind a server (with four parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(toHost(host, port), materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    "properly bind a server (with five parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(toHost(host, port), serverSettings, materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    "properly bind a server (with six parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(toHost(host, port), serverSettings, loggingAdapter, materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    // this cover both bind and single request
    "properly bind and handle a server with a flow (with four parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req ⇒ HttpResponse.create())
        .asJava
      val binding = http.bindAndHandle(flow, toHost(host, port), materializer)

      val (_, completion) = http.outgoingConnection(toHost(host, port))
        .runWith(Source.single(HttpRequest.create("/abc")), Sink.head(), materializer).toScala

      waitFor(completion)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a flow (with five parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req ⇒ HttpResponse.create())
        .asJava
      val binding = http.bindAndHandle(flow, toHost(host, port), materializer)

      val (_, completion) = http.outgoingConnection(toHost(host, port))
        .runWith(Source.single(HttpRequest.create("/abc")), Sink.head(), materializer).toScala

      waitFor(completion)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a flow (with seven parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req ⇒ HttpResponse.create())
        .asJava
      val binding = http.bindAndHandle(flow, toHost(host, port), serverSettings, loggingAdapter, materializer)

      val (_, completion) = http.outgoingConnection(toHost(host, port))
        .runWith(Source.single(HttpRequest.create("/abc")), Sink.head(), materializer).toScala

      waitFor(completion)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a synchronous function (with four parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleSync(httpSuccessFunction, toHost(host, port), materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a synchronous function (with five parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleSync(httpSuccessFunction, toHost(host, port), materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a synchronous (with seven parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleSync(httpSuccessFunction, toHost(host, port), serverSettings, loggingAdapter, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with an asynchronous function (with four parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleAsync(asyncHttpSuccessFunction, toHost(host, port), materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with an asynchronous function (with five parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleAsync(asyncHttpSuccessFunction, toHost(host, port), materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with an asynchronous function (with eight parameters)" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleAsync(asyncHttpSuccessFunction, toHost(host, port), serverSettings, 1, loggingAdapter, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "have serverLayer methods" in {
      // TODO actually cover these with runtime tests, compile only for now
      pending

      http.serverLayer(materializer)

      val serverSettings = ServerSettings.create(system)
      http.serverLayer(serverSettings, materializer)

      val remoteAddress = Optional.empty[InetSocketAddress]()
      http.serverLayer(serverSettings, remoteAddress, materializer)

      val loggingAdapter = NoLogging
      http.serverLayer(serverSettings, remoteAddress, loggingAdapter, materializer)
    }

    "create a cached connection pool (with a ConnectToHttp and a materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](toHost(host, port), materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.isSuccess should be(true)
      binding.unbind()
    }

    "create a cached connection pool to a https server (with four parameters)" in {
      // requires https
      pending
      val (host, port, binding) = runServer()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](toHost(host, port), poolSettings, loggingAdapter, materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "create a cached connection pool (with a String and a Materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](s"http://$host:$port", materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "create a host connection pool (with a ConnectHttp and a Materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow = http.newHostConnectionPool[NotUsed](toHost(host, port), materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(get(host, port), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      pair.first.shutdown(system.dispatcher)
      binding.unbind()
    }

    "create a host connection pool to a https server (with four parameters)" in {
      // requires https
      pending
      val (host, port, binding) = runServer()

      val poolFlow = http.newHostConnectionPool[NotUsed](toHost(host, port), poolSettings, loggingAdapter, materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(get(host, port), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      pair.first.shutdown(system.dispatcher)
      binding.unbind()
    }

    "create a host connection pool (with a String and a Materializer)" in {
      val (host, port, binding) = runServer()

      val poolFlow = http.newHostConnectionPool[NotUsed](s"http://$host:$port", materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(get(host, port), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      waitFor(pair.second).first.get.status() should be(StatusCodes.OK)
      pair.first.shutdown(system.dispatcher)
      binding.unbind()
    }

    "allow access to the default client https context" in {
      http.defaultClientHttpsContext.isSecure should equal(true)
    }

    "allow access to the default server https context" in {
      http.defaultServerHttpContext.isSecure should equal(false)
    }

    "have client layer methods" in {
      // TODO actually cover these with runtime tests, compile only for now
      pending
      val connectionSettings = ClientConnectionSettings.create(system)
      http.clientLayer(headers.Host.create("example.com"))
      http.clientLayer(headers.Host.create("example.com"), connectionSettings)
      http.clientLayer(headers.Host.create("example.com"), connectionSettings, loggingAdapter)
    }

    "create an outgoing connection (with a string)" in {
      // this one cannot be tested because it wants to run on port 80
      pending
      http.outgoingConnection("example.com"): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]]
    }

    "create an outgoing connection (with a ConnectHttp)" in {
      val (host, port, binding) = runServer()
      val flow = http.outgoingConnection(toHost(host, port))

      val response = Source.single(get(host, port))
        .via(flow)
        .toMat(Sink.head(), Keep.right[NotUsed, CompletionStage[HttpResponse]])
        .run(materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "create an outgoing connection (with 6 parameters)" in {
      val (host, port, binding) = runServer()
      val flow = http.outgoingConnection(
        toHost(host, port),
        Optional.empty(),
        ClientConnectionSettings.create(system),
        NoLogging)

      val response = Source.single(get(host, port))
        .via(flow)
        .toMat(Sink.head(), Keep.right[NotUsed, CompletionStage[HttpResponse]])
        .run(materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "allow a single request (with two parameters)" in {
      val (host, port, binding) = runServer()
      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "allow a single request (with three parameters)" in {
      val (host, port, binding) = runServer()
      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), http.defaultClientHttpsContext, materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "allow a single request (with five parameters)" in {
      val (host, port, binding) = runServer()
      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), http.defaultClientHttpsContext, poolSettings, loggingAdapter, materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "interact with a websocket through a flow (with with one parameter)" in {
      val (host, port, binding) = runWebsocketServer()
      val flow = http.webSocketClientFlow(WebSocketRequest.create(s"ws://$host:$port"))
      val pair = Source.single(TextMessage.create("hello"))
        .viaMat(flow, Keep.right[NotUsed, CompletionStage[WebSocketUpgradeResponse]])
        .toMat(Sink.head[Message](), Keep.both[CompletionStage[WebSocketUpgradeResponse], CompletionStage[Message]])
        .run(materializer)

      waitFor(pair.first).response.status() should be(StatusCodes.SWITCHING_PROTOCOLS)
      waitFor(pair.second).asTextMessage.getStrictText should be("hello")
      binding.unbind()
    }

    "interact with a websocket through a flow (with five parameters)" in {
      val (host, port, binding) = runWebsocketServer()
      val flow = http.webSocketClientFlow(WebSocketRequest.create(s"ws://$host:$port"), connectionContext, Optional.empty(), ClientConnectionSettings.create(system), loggingAdapter)
      val pair = Source.single(TextMessage.create("hello"))
        .viaMat(flow, Keep.right[NotUsed, CompletionStage[WebSocketUpgradeResponse]])
        .toMat(Sink.head[Message](), Keep.both[CompletionStage[WebSocketUpgradeResponse], CompletionStage[Message]])
        .run(materializer)

      waitFor(pair.first).response.status() should be(StatusCodes.SWITCHING_PROTOCOLS)
      waitFor(pair.second).asTextMessage.getStrictText should be("hello")
      binding.unbind()
    }

  }

  def get(host: Host, port: Port) = HttpRequest.GET("/").addHeader(headers.Host.create(host, port))

  def runServer(): (Host, Port, ServerBinding) = {
    val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
    val server = http.bindAndHandleSync(httpSuccessFunction, toHost(host, port), materializer)

    (host, port, waitFor(server))
  }

  def runWebsocketServer(): (Host, Port, ServerBinding) = {
    val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
    val server = http.bindAndHandleSync(new Function[HttpRequest, HttpResponse] {

      override def apply(request: HttpRequest): HttpResponse = {
        WebSocket.handleWebSocketRequestWith(request, Flow.create[Message]())
      }
    }, toHost(host, port), materializer)

    (host, port, waitFor(server))
  }

  private def waitFor[T](completionStage: CompletionStage[T]): T =
    completionStage.toCompletableFuture.get(3, TimeUnit.SECONDS)

  override protected def afterAll() = TestKit.shutdownActorSystem(system)
}
