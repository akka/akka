/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.{ CompletionStage, TimeUnit, CompletableFuture }

import akka.NotUsed
import akka.http.impl.util.JavaMapping.HttpsConnectionContext
import akka.japi.Pair
import akka.actor.ActorSystem
import akka.event.{ NoLogging, LoggingAdapterTest }
import akka.http.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import akka.http.javadsl.model._
import akka.http.scaladsl.TestUtils
import akka.japi.Function
import akka.stream.{ Materializer, ActorMaterializer }
import akka.stream.javadsl.{ Source, Flow, Sink, Keep }
import akka.stream.testkit.TestSubscriber
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ WordSpec, Matchers, BeforeAndAfterAll }
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class HttpExtensionApiSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {

  // tries to cover all surface area of javadsl.Http

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
    "properly bind a server 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(host, port, materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      val address = waitFor(binding).localAddress
      sub.cancel()
    }

    "properly bind a server 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(host, port, connectionContext, materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      val address = waitFor(binding).localAddress
      sub.cancel()
    }

    "properly bind a server 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(host, port, connectionContext, serverSettings, materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      val address = waitFor(binding).localAddress
      sub.cancel()
    }

    "properly bind a server 4" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val probe = TestSubscriber.manualProbe[IncomingConnection]()
      val binding = http.bind(host, port, connectionContext, serverSettings, loggingAdapter, materializer)
        .toMat(Sink.fromSubscriber(probe), Keep.left)
        .run(materializer)
      val sub = probe.expectSubscription()
      waitFor(binding).unbind()
      sub.cancel()
    }

    // this cover both bind and single request
    "properly bind and handle a server with a flow 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req ⇒ HttpResponse.create())
        .asJava
      val binding = http.bindAndHandle(flow, host, port, materializer)

      val (_, completion) = http.outgoingConnection(ConnectHttp.toHost(host, port))
        .runWith(Source.single(HttpRequest.create("/abc")), Sink.head(), materializer).toScala

      waitFor(completion)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a flow 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req ⇒ HttpResponse.create())
        .asJava
      val binding = http.bindAndHandle(flow, host, port, connectionContext, materializer)

      val (_, completion) = http.outgoingConnection(ConnectHttp.toHost(host, port))
        .runWith(Source.single(HttpRequest.create("/abc")), Sink.head(), materializer).toScala

      waitFor(completion)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a flow 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val flow: Flow[HttpRequest, HttpResponse, NotUsed] = akka.stream.scaladsl.Flow[HttpRequest]
        .map(req ⇒ HttpResponse.create())
        .asJava
      val binding = http.bindAndHandle(flow, host, port, serverSettings, connectionContext, loggingAdapter, materializer)

      val (_, completion) = http.outgoingConnection(ConnectHttp.toHost(host, port))
        .runWith(Source.single(HttpRequest.create("/abc")), Sink.head(), materializer).toScala

      waitFor(completion)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a synchronous function 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleSync(httpSuccessFunction, host, port, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a synchronous function 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleSync(httpSuccessFunction, host, port, connectionContext, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with a synchronous function 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleSync(httpSuccessFunction, host, port, serverSettings, connectionContext, loggingAdapter, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with an asynchronous function 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleAsync(asyncHttpSuccessFunction, host, port, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with an asynchronous function 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleAsync(asyncHttpSuccessFunction, host, port, connectionContext, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "properly bind and handle a server with an asynchronous function 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = http.bindAndHandleAsync(asyncHttpSuccessFunction, host, port, serverSettings, connectionContext, 1, loggingAdapter, materializer)

      val response = http.singleRequest(HttpRequest.create(s"http://$host:$port/").withMethod(HttpMethods.GET), materializer)

      waitFor(response)
      waitFor(binding).unbind()
    }

    "have serverLayer methods" in {
      // compile only for now
      pending

      http.serverLayer(materializer)

      val serverSettings = ServerSettings.create(system)
      http.serverLayer(serverSettings, materializer)

      val remoteAddress = Optional.empty[InetSocketAddress]()
      http.serverLayer(serverSettings, remoteAddress, materializer)

      val loggingAdapter = NoLogging
      http.serverLayer(serverSettings, remoteAddress, loggingAdapter, materializer)
    }

    "create a cached connection pool 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](ConnectHttp.toHost(host, port), materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET(s"http://$host:$port/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      val response = pair.second.toCompletableFuture.get(1, TimeUnit.SECONDS)
    }

    "create a cached connection pool 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](ConnectHttp.toHost(host, port), poolSettings, loggingAdapter, materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET("/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      val response = pair.second.toCompletableFuture.get(1, TimeUnit.SECONDS)
    }

    "create a cached connection pool 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val poolFlow: Flow[Pair[HttpRequest, NotUsed], Pair[Try[HttpResponse], NotUsed], HostConnectionPool] =
        http.cachedHostConnectionPool[NotUsed](s"$host:$port", materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET("/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      val response = pair.second.toCompletableFuture.get(1, TimeUnit.SECONDS)
    }

    "create a host connection pool 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val poolFlow = http.newHostConnectionPool[NotUsed](ConnectHttp.toHost(host, port), materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET("/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      val response = pair.second.toCompletableFuture.get(1, TimeUnit.SECONDS)
    }

    "create a host connection pool 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val poolFlow = http.newHostConnectionPool[NotUsed](ConnectHttp.toHost(host, port), poolSettings, loggingAdapter, materializer)

      val pair: Pair[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]] =
        Source.single(new Pair(HttpRequest.GET("/"), NotUsed.getInstance()))
          .viaMat(poolFlow, Keep.right[NotUsed, HostConnectionPool])
          .toMat(
            Sink.head(),
            Keep.both[HostConnectionPool, CompletionStage[Pair[Try[HttpResponse], NotUsed]]])
          .run(materializer)

      val response = pair.second.toCompletableFuture.get(1, TimeUnit.SECONDS)
    }

    "allow access to the default client https context" in {
      http.defaultClientHttpsContext.isSecure should equal(true)
    }

    "allow access to the default server https context" in {
      http.defaultServerHttpContext.isSecure should equal(false)
    }

    "have client layer methods" in {
      // TODO actually cover these with tests somehow
      pending
      http.clientLayer(headers.Host.create("example.com"))
      http.clientLayer(headers.Host.create("example.com"), connectionSettings)
      http.clientLayer(headers.Host.create("example.com"), connectionSettings, loggingAdapter)
    }

    "create an outgoing connection 1" in {
      // this one cannot be tested because it wants to run on port 80
      pending
      val flow: Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]] = http.outgoingConnection("example.com")

    }

    "create an outgoing connection 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val server = http.bindAndHandleSync(httpSuccessFunction, host, port, materializer)
      val binding = waitFor(server)

      val flow = http.outgoingConnection(ConnectHttp.toHost(host, port))

      val response = Source.single(HttpRequest.GET("/").addHeader(headers.Host.create(host, port)))
        .via(flow)
        .toMat(Sink.head(), Keep.right[NotUsed, CompletionStage[HttpResponse]])
        .run(materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "create an outgoing connection 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val server = http.bindAndHandleSync(httpSuccessFunction, host, port, materializer)
      val binding = waitFor(server)
      val flow = http.outgoingConnection(ConnectHttp.toHost(host, port))

      val response = Source.single(HttpRequest.GET(s"http://$host:$port/").addHeader(headers.Host.create(host, port)))
        .via(flow)
        .toMat(Sink.head(), Keep.right[NotUsed, CompletionStage[HttpResponse]])
        .run(materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      binding.unbind()
    }

    "allow a single request 1" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val server = http.bindAndHandleSync(httpSuccessFunction, host, port, materializer)

      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(server).unbind()
    }

    "allow a single request 2" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val server = http.bindAndHandleSync(httpSuccessFunction, host, port, materializer)

      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), http.defaultClientHttpsContext, materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(server).unbind()
    }

    "allow a single request 3" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val server = http.bindAndHandleSync(httpSuccessFunction, host, port, materializer)

      val response = http.singleRequest(HttpRequest.GET(s"http://$host:$port/"), http.defaultClientHttpsContext, poolSettings, loggingAdapter, materializer)

      waitFor(response).status() should be(StatusCodes.OK)
      waitFor(server).unbind()
    }

  }

  private def waitFor[T](completionStage: CompletionStage[T]): T =
    completionStage.toCompletableFuture.get(1, TimeUnit.SECONDS)

  override protected def afterAll(): Unit = {
    system.terminate()
  }
}
