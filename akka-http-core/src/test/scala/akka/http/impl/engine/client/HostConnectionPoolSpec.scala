/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.ActorSystem
import akka.http.impl.util._
import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolFlow.{ RequestContext, ResponseContext }
import akka.http.impl.engine.client.pool.NewHostConnectionPool
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.scaladsl.{ ClientTransport, ConnectionContext, Http }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import akka.stream._
import akka.stream.scaladsl.{ BidiFlow, Flow, GraphDSL, Keep, Sink, Source, TLSPlacebo }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit._
import akka.util.ByteString
import org.reactivestreams.{ Publisher, Subscriber }
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.Failure

/**
 * Tests the host connection pool infrastructure.
 *
 * Right now it tests against various stacks with various depths. It's debatable whether it should actually be tested
 * against plain network bytes instead to show interaction on the HTTP protocol level instead of against the server
 * API level.
 */
class HostConnectionPoolSpec extends AkkaSpec(
  """
     akka.loglevel = INFO
     akka.actor {
       serialize-creators = off
       serialize-messages = off
       default-dispatcher.throughput = 100
     }
     akka.http.client.log-unencrypted-network-bytes = 200
  """
) with Eventually {
  implicit val materializer = ActorMaterializer()
  val singleElementBufferMaterializer = materializer // ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(1, 1))
  val defaultSettings =
    ConnectionPoolSettings(system)
      .withMaxConnections(1)

  trait PoolImplementation {
    def get: (Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]], ConnectionPoolSettings, LoggingAdapter) ⇒ Flow[RequestContext, ResponseContext, Any]
  }
  trait ClientServerImplementation {
    /** Returns a client / server implementation that include the kill switch flow in the middle */
    def get(connectionKillSwitch: SharedKillSwitch): BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]]

    /**
     * Specifies if the transport implementation will fail the handler request input side if an error is encountered
     * at the response output side.
     *
     * I haven't decided yet what the right behavior should be.
     */
    def failsHandlerInputWhenHandlerOutputFails: Boolean
  }

  testSet(poolImplementation = NewPoolImplementation, clientServerImplementation = PassThrough)
  testSet(poolImplementation = NewPoolImplementation, clientServerImplementation = AkkaHttpEngineNoNetwork)
  testSet(poolImplementation = NewPoolImplementation, clientServerImplementation = AkkaHttpEngineTCP)
  //testSet(poolImplementation = NewPoolImplementation, clientServerImplementation = AkkaHttpEngineTLS)

  testSet(poolImplementation = LegacyPoolImplementation, clientServerImplementation = PassThrough)
  testSet(poolImplementation = LegacyPoolImplementation, clientServerImplementation = AkkaHttpEngineNoNetwork)
  testSet(poolImplementation = LegacyPoolImplementation, clientServerImplementation = AkkaHttpEngineTCP)
  //testSet(poolImplementation = OldPoolImplementation, clientServerImplementation = AkkaHttpEngineTLS)

  def testSet(poolImplementation: PoolImplementation, clientServerImplementation: ClientServerImplementation) =
    s"$poolImplementation on $clientServerImplementation" should {
      "complete a simple request/response cycle with a strict request and response" in new SetupWithServerProbes {
        pushRequest(HttpRequest(uri = "/simple"))

        val conn1 = expectNextConnection()
        val req = conn1.expectRequest()
        conn1.pushResponse(HttpResponse(entity = req.uri.path.toString))
        expectResponseEntityAsString() shouldEqual "/simple"
      }
      "complete a simple request/response cycle with a chunked request and response" in new SetupWithServerProbes {
        val reqBody = Source("Hello" :: " World" :: Nil map ByteString.apply)
        pushRequest(HttpRequest(uri = "/simple", entity = HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, reqBody)))

        val conn1 = expectNextConnection()
        val HttpRequest(_, _, _, reqEntityIn: HttpEntity.Chunked, _) = conn1.expectRequest()
        reqEntityIn.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(3.seconds).utf8String shouldEqual "Hello World"

        val resBodyOut = conn1.pushChunkedResponse()
        val resBodyIn = expectChunkedResponseBytesAsProbe()

        resBodyOut.sendNext(ByteString("hi"))
        resBodyIn.expectUtf8EncodedString("hi")

        resBodyOut.sendComplete()
        resBodyIn.request(1) // FIXME: should we support eager completion here? (reason is substreamHandler in PrepareResponse)
        resBodyIn.expectComplete()
      }
      "open up to max-connections when enough requests are pending" in new SetupWithServerProbes(_.withMaxConnections(2)) {
        pushRequest(HttpRequest(uri = "/1"))
        val conn1 = expectNextConnection()
        conn1.expectRequestToPath("/1")

        pushRequest(HttpRequest(uri = "/2"))
        val conn2 = expectNextConnection()
        conn2.expectRequestToPath("/2")

        pushRequest(HttpRequest(uri = "/3"))
        conn1.pushResponse(HttpResponse())
        expectResponse()
        conn1.expectRequestToPath("/3")
      }
      "only buffer a reasonable number of extra requests" in pending
      "only send next request when last response entity was read completely" in new SetupWithServerProbes() {
        pushRequest(HttpRequest(uri = "/chunked-1"))
        pushRequest(HttpRequest(uri = "/2"))
        val conn1 = expectNextConnection()
        conn1.expectRequestToPath("/chunked-1")
        //FIXME: expectNoNewConnection()
        //conn1.expectNoRequest()

        val resp1BytesOut = conn1.pushChunkedResponse()
        val resp1BytesIn = expectChunkedResponseBytesAsProbe()
        resp1BytesOut.sendNext(ByteString("test"))
        resp1BytesIn.expectUtf8EncodedString("test")

        // FIXME: expectNoNewConnection()
        // conn1.expectNoRequest()

        resp1BytesOut.sendComplete()
        resp1BytesIn.request(1) // FIXME: should we support eager completion here?
        resp1BytesIn.expectComplete()

        conn1.expectRequestToPath("/2")
      }
      "time out quickly when response entity stream is not subscribed fast enough" in new SetupWithServerProbes {
        pendingIn(targetImpl = LegacyPoolImplementation) // not implemented in legacy
        pendingIn(targetTrans = PassThrough) // infra seems to be missing something

        // FIXME: set subscription timeout to value relating to below `expectNoMsg`

        pushRequest(HttpRequest(uri = "/1"))
        val conn1 = expectNextConnection()
        conn1.expectRequestToPath("/1")

        val (resBodyOut, chunks) =
          EventFilter.warning(pattern = ".*Response entity was not subscribed.*", occurrences = 1) intercept {
            val resBodyOut = conn1.pushChunkedResponse()
            val HttpResponse(_, _, HttpEntity.Chunked(_, chunks), _) = expectResponse()
            (resBodyOut, chunks)
          }

        val streamResult = chunks.runWith(Sink.ignore)
        Await.ready(streamResult, 3.seconds)
        streamResult.value.get.failed.get.getMessage shouldEqual "Substream Source cannot be materialized more than once"
      }
      "time out when a connection was unused for a long time" in pending
      "time out and reconnect when a request is not handled in time" in pending
      "time out when connection cannot be established" in pending
      "fail a request if the request entity fails" in new SetupWithServerProbes {
        val reqBytesOut = pushChunkedRequest()

        val conn1 = expectNextConnection()
        val reqBytesIn = conn1.expectChunkedRequestBytesAsProbe()
        reqBytesOut.sendNext(ByteString("hello"))
        reqBytesIn.expectUtf8EncodedString("hello")

        reqBytesOut.sendError(new RuntimeException("oops, could not finish sending request"))

        // expectRequestStreamError(reqBytesIn)

        // FIXME: this is currently part of the implementation that is not tested (request entity error will fail the connection)
        // conn1.serverRequests.expectError()
        responseOut.expectSubscription()
        // FIXME: currently the API is weird in that it contains a promise which is completed with a failure instead
        //        of properly threading through the context field from request to response
        // responseOut.expectError() // actually, only the response should be failed
      }
      "fail a request if the connection stream fails while waiting for request entity bytes" in new SetupWithServerProbes {
        val reqBytesOut = pushChunkedRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)

        val conn1 = expectNextConnection()
        val reqBytesIn = conn1.expectChunkedRequestBytesAsProbe()

        reqBytesOut.sendNext(ByteString("chunk1"))
        reqBytesIn.expectUtf8EncodedString("chunk1")

        conn1.failConnection(new RuntimeException("server temporarily out for lunch"))

        // server behavior not tested for now
        // expectRequestStreamError(reqBytesIn) // some kind of truncation error
        // reqBytesOut.expectCancellation()
        expectResponseError()
      }
      "fail a request if the connection stream fails while waiting for a response" in new SetupWithServerProbes {
        pushRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)
        val conn1 = expectNextConnection()
        conn1.expectRequest()

        conn1.failConnection(new RuntimeException("solar wind prevented transmission"))
        expectResponseError()
      }
      "fail a request if the connection stream fails while waiting for response entity bytes" in new SetupWithServerProbes {
        pushRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)
        val conn1 = expectNextConnection()
        conn1.expectRequest()
        val resBytesOut = conn1.pushChunkedResponse()
        val resBytesIn = expectChunkedResponseBytesAsProbe()
        resBytesOut.sendNext(ByteString("hello"))
        resBytesIn.expectUtf8EncodedString("hello")

        conn1.failConnection(new RuntimeException("solar wind prevented transmission"))
        // server behavior not tested for now
        // resBytesIn.expectError()

        // client already received response, no need to report error another time
      }
      "fail a request if the response entity stream fails during processing" in new SetupWithServerProbes {
        pushRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)
        val conn1 = expectNextConnection()
        conn1.expectRequest()
        val resBytesOut = conn1.pushChunkedResponse()
        val resBytesIn = expectChunkedResponseBytesAsProbe()
        resBytesOut.sendNext(ByteString("hello"))
        resBytesIn.expectUtf8EncodedString("hello")

        resBytesOut.sendError(new RuntimeException("hard disk too soft for reading further"))
        resBytesIn.expectError()
        conn1.expectError()

        // client already received response, no need to report error another time
      }
      "create a new connection when previous one was closed regularly between requests" in new SetupWithServerProbes {
        pendingIn(targetImpl = LegacyPoolImplementation) // flaky test, no reason to debug old client pool issues for now
        pushRequest(HttpRequest(uri = "/simple"))

        val conn1 = expectNextConnection()
        val req = conn1.expectRequest()
        conn1.pushResponse(HttpResponse(headers = headers.Connection("close") :: Nil, entity = req.uri.path.toString))
        expectResponseEntityAsString() shouldEqual "/simple"
        conn1.completeHandler()

        pushRequest(HttpRequest(uri = "/next"))
        val conn2 = expectNextConnection()
        conn2.expectRequestToPath("/next")
        conn2.pushResponse(HttpResponse(entity = "response"))
        expectResponseEntityAsString() shouldEqual "response"
      }
      "create a new connection when previous one was closed regularly between requests without sending a `Connection: close` header first" in new SetupWithServerProbes {
        pendingIn(targetImpl = LegacyPoolImplementation) // flaky test, no reason to debug old client pool issues for now
        pushRequest(HttpRequest(uri = "/simple"))

        val conn1 = expectNextConnection()
        val req = conn1.expectRequest()
        conn1.pushResponse(HttpResponse(entity = req.uri.path.toString))
        expectResponseEntityAsString() shouldEqual "/simple"
        conn1.completeHandler()

        // Here's an inherent race condition: we might accidentally schedule the next request on the just-completing
        // connection. So we add a sleep to increase chances, we've been in the Unconnected state before the new request
        // is dispatched. If connection still happens to be in the Idle state, the request should be transparently
        // retried.
        Thread.sleep(100)

        pushRequest(HttpRequest(uri = "/next"))
        val conn2 = expectNextConnection()
        conn2.expectRequestToPath("/next")
        conn2.pushResponse(HttpResponse(entity = "response"))
        expectResponseEntityAsString() shouldEqual "response"
      }
      "create a new connection when previous one failed between requests" in new SetupWithServerProbes {
        pendingIn(targetImpl = LegacyPoolImplementation) // flaky test, no reason to debug old client pool issues for now
        pushRequest(HttpRequest(uri = "/simple"))

        val conn1 = expectNextConnection()
        val req = conn1.expectRequest()
        conn1.pushResponse(HttpResponse(entity = req.uri.path.toString))
        expectResponseEntityAsString() shouldEqual "/simple"
        conn1.failConnection(new RuntimeException("broken connection"))

        pushRequest(HttpRequest(uri = "/next"))
        val conn2 = expectNextConnection()
        conn2.expectRequestToPath("/next")
        conn2.pushResponse(HttpResponse(entity = "response"))
        expectResponseEntityAsString() shouldEqual "response"
      }
      "support 100-continue" in pending
      "without any connections establish the number of configured min-connections" in new SetupWithServerProbes(_.withMaxConnections(2).withMinConnections(1)) {
        // expect a new connection immediately
        val conn1 = expectNextConnection()

        // should be used for the first request
        pushRequest(HttpRequest(uri = "/simple"))
        conn1.expectRequest()
      }
      "re-establish min-connections when number of open connections falls below threshold" in new SetupWithServerProbes(_.withMaxConnections(2).withMinConnections(1)) {
        pendingIn(targetImpl = LegacyPoolImplementation) // has failed a few times but I didn't check why exactly

        // expect a new connection immediately
        val conn1 = expectNextConnection()

        // should be used for the first request
        pushRequest(HttpRequest(uri = "/simple"))
        conn1.expectRequestToPath("/simple")
        conn1.pushResponse(HttpResponse(headers = headers.Connection("close") :: Nil))
        expectResponse()
        conn1.completeConnection()

        expectNextConnection()
      }
      "not buffer an unreasonable number of outgoing responses" in new SetupWithServerProbes(_.withMaxConnections(1).withMinConnections(1)) {
        val conn1 = expectNextConnection()

        def oneCycle(): Unit = {
          pushRequest()
          conn1.expectRequest(within = 100.millis.dilated)
          conn1.pushResponse()
        }

        eventually {
          // should fail eventually because backpressure kicks in and one of the expects / pushes above will timeout
          a[Throwable] should be thrownBy oneCycle()
        }
      }
      "not send requests to known-to-be-closed-soon connections" in pending
      "support retries" in pending
      "strictly enforce number of established connections in longer running case" in pending
      "provide access to basic metrics as the materialized value" in pending
      "ignore the pipelining setting (for now)" in pending
      "work correctly in the presence of `Connection: close` headers" in pending
      "if connecting attempt fails, backup the next connection attempts" in pending

      def pendingIn(targetImpl: PoolImplementation = null, targetTrans: ClientServerImplementation = null): Unit =
        if ((targetImpl == null || poolImplementation == targetImpl) &&
          (targetTrans == null || clientServerImplementation == targetTrans))
          pending

      abstract class TestSetup {
        lazy val requestIn = TestPublisher.probe[RequestContext]()
        lazy val responseOut = TestSubscriber.probe[ResponseContext]()

        protected val server: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]

        protected def settings: ConnectionPoolSettings

        lazy val impl = poolImplementation.get(
          server,
          settings,
          system.log
        )
        val stream =
          Source.fromPublisher(requestIn)
            .via(impl)
            .runWith(Sink.fromSubscriber(responseOut))

        def pushRequest(req: HttpRequest = HttpRequest(), numRetries: Int = 5): Unit =
          requestIn.sendNext(RequestContext(req, Promise(), numRetries))

        def pushChunkedRequest(req: HttpRequest = HttpRequest(), numRetries: Int = 5): TestPublisher.Probe[ByteString] = {
          val probe = TestPublisher.probe[ByteString]()
          pushRequest(req.withEntity(HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, Source.fromPublisher(probe))), numRetries)
          probe
        }

        def expectResponse(): HttpResponse =
          responseOut.requestNext().response.recover {
            case ex ⇒ throw new AssertionError("Expected successful response but got exception", ex)
          }.get

        def expectResponseEntityAsString(): String =
          expectResponse().entity.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(5.seconds.dilated).utf8String

        /** Expect a chunked response, connect a [[ByteStringSinkProbe]] to it and return it */
        def expectChunkedResponseBytesAsProbe(): ByteStringSinkProbe = {
          val HttpResponse(_, _, entity: HttpEntity.Chunked, _) = expectResponse()
          val probe = ByteStringSinkProbe()
          entity.dataBytes.runWith(probe.sink)
          probe
        }

        def expectNoRequestDemand(): Unit =
          requestIn.pending shouldEqual 0

        def expectResponseError(): Throwable =
          responseOut.requestNext().response.failed.get
      }

      class SetupWithServerProbes(changeSettings: ConnectionPoolSettings ⇒ ConnectionPoolSettings = identity) extends TestSetup {
        override protected def settings = changeSettings(defaultSettings)

        class ServerConnection(requestPublisher: Publisher[HttpRequest], responseSubscriber: Subscriber[HttpResponse]) {
          val serverRequests = TestSubscriber.probe[HttpRequest]()
          val serverResponses = TestPublisher.probe[HttpResponse]()
          val killSwitch = KillSwitches.shared("connection-kill-switch")

          def expectRequest(): HttpRequest =
            serverRequests.requestNext()

          def expectRequest(within: FiniteDuration): HttpRequest =
            serverRequests.within(within)(serverRequests.requestNext())

          def expectRequestToPath(path: String): Unit =
            expectRequest().uri.path.toString shouldEqual path

          /** Expect a chunked response, connect a [[ByteStringSinkProbe]] to it and return it */
          def expectChunkedRequestBytesAsProbe(): ByteStringSinkProbe = {
            val HttpRequest(_, _, _, entity: HttpEntity.Chunked, _) = expectRequest()
            val probe = ByteStringSinkProbe()
            entity.dataBytes.runWith(probe.sink)
            probe
          }

          def expectNoRequest(): Unit =
            serverRequests.expectNoMsg()

          def pushResponse(response: HttpResponse = HttpResponse()) =
            serverResponses.sendNext(response)

          def pushChunkedResponse(response: HttpResponse = HttpResponse()): TestPublisher.Probe[ByteString] = {
            val res = TestPublisher.probe[ByteString]()
            pushResponse(response.withEntity(HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, Source.fromPublisher(res))))
            res
          }

          def completeHandler(): Unit = {
            serverResponses.sendComplete()
            serverRequests.expectComplete()
          }

          def failConnection(cause: Exception): Unit =
            killSwitch.abort(cause)

          def completeConnection(): Unit =
            killSwitch.shutdown()

          def failHandler(cause: Exception): Unit = {
            serverResponses.sendError(cause)
            // since this is server behavior, it's not really important to check it here
            // FIXME: verify server behavior
            expectErrorOrCompleteOnRequestSide()
          }
          def expectError(): Unit = {
            serverResponses.expectCancellation()
            expectErrorOrCompleteOnRequestSide()
          }
          def expectErrorOrCompleteOnRequestSide(): Unit =
            serverRequests.expectEventPF {
              case _: TestSubscriber.OnError ⇒
              case TestSubscriber.OnComplete ⇒
            }

          lazy val (outgoingConnection: Future[Http.OutgoingConnection], terminationWatch: Future[Done]) =
            Flow.fromSinkAndSource(
              Sink.fromSubscriber(serverRequests),
              Source.fromPublisher(serverResponses))
              .joinMat(clientServerImplementation.get(killSwitch))(Keep.right)
              .watchTermination()(Keep.both)
              .join(
                Flow.fromSinkAndSource(
                  Sink.fromSubscriber(responseSubscriber),
                  Source.fromPublisher(requestPublisher)
                ))
              .run()(singleElementBufferMaterializer)
        }

        private lazy val serverConnections = TestProbe()

        def expectNextConnection(): ServerConnection =
          serverConnections.expectMsgType[ServerConnection]

        def expectNoNewConnection(): Unit =
          serverConnections.expectNoMsg()

        protected override lazy val server =
          Flow.fromSinkAndSourceMat(
            // buffer is needed because the async subscriber/publisher boundary will otherwise request > 1
            Flow[HttpRequest].buffer(1, OverflowStrategy.backpressure)
              .toMat(Sink.asPublisher[HttpRequest](false))(Keep.right),
            Source.asSubscriber[HttpResponse])(Keep.both)
            .mapMaterializedValue {
              case (requestPublisher, responseSubscriber) ⇒
                val connection = new ServerConnection(requestPublisher, responseSubscriber)
                serverConnections.ref ! connection
                connection.outgoingConnection
            }
      }
    }

  case object LegacyPoolImplementation extends PoolImplementation {
    override def get = PoolFlow(_, _, _)
  }
  case object NewPoolImplementation extends PoolImplementation {
    override def get = NewHostConnectionPool(_, _, _)
  }

  /** Transport that just passes through requests / responses */
  case object PassThrough extends ClientServerImplementation {
    def failsHandlerInputWhenHandlerOutputFails: Boolean = true
    override def get(connectionKillSwitch: SharedKillSwitch): BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]] =
      BidiFlow.fromGraph(PassThroughTransport)
        .atop(BidiFlow.fromFlows(connectionKillSwitch.flow[HttpResponse], connectionKillSwitch.flow[HttpRequest]))
        .mapMaterializedValue(_ ⇒ Future.successful(newOutgoingConnection()))

    object PassThroughTransport extends GraphStage[BidiShape[HttpResponse, HttpResponse, HttpRequest, HttpRequest]] {
      val reqIn = Inlet[HttpRequest]("reqIn")
      val reqOut = Outlet[HttpRequest]("reqOut")
      val resIn = Inlet[HttpResponse]("resIn")
      val resOut = Outlet[HttpResponse]("resOut")

      val shape = BidiShape(resIn, resOut, reqIn, reqOut)

      def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
        val failureCallback = getAsyncCallback[Throwable](cause ⇒ failStage(cause))
        val killSwitch = KillSwitches.shared("entity")

        object AddKillSwitch extends StreamUtils.EntityStreamOp[Unit] {
          def strictM: Unit = ()
          def apply[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Unit) =
            (source.via(killSwitch.flow[T]), ())
        }
        class MonitorMessage[T <: HttpMessage](in: Inlet[T], out: Outlet[T]) extends InHandler with OutHandler {

          def onPush(): Unit = {
            val msg: T = grab(in)

            val (newEntity, res) =
              HttpEntity.captureTermination(msg.entity)

            val finalMsg: T = msg.withEntity(
              StreamUtils.transformEntityStream(newEntity, AddKillSwitch)._1
                .asInstanceOf[MessageEntity]).asInstanceOf[T] // FIXME: that cast is probably unsafe for CloseLimited

            res.onComplete { // if entity fails we report back to fail the stage
              case Failure(cause) ⇒ failureCallback.invoke(cause)
              case _              ⇒
            }(materializer.executionContext)

            push(out, finalMsg)
          }
          def onPull(): Unit = pull(in)

          override def onUpstreamFailure(ex: Throwable): Unit = {
            killSwitch.abort(ex)
            super.onUpstreamFailure(ex)
          }

          override def onDownstreamFinish(): Unit = failStage(new RuntimeException("was cancelled"))
        }
        setHandlers(reqIn, reqOut, new MonitorMessage(reqIn, reqOut))
        setHandlers(resIn, resOut, new MonitorMessage(resIn, resOut))
      }
    }
  }
  /** Transport that runs everything through client and server engines but without actual network */
  case object AkkaHttpEngineNoNetwork extends ClientServerImplementation {
    def failsHandlerInputWhenHandlerOutputFails: Boolean = false

    override def get(connectionKillSwitch: SharedKillSwitch): BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]] =
      Http().serverLayer() atop
        TLSPlacebo() atop
        BidiFlow.fromFlows(connectionKillSwitch.flow[ByteString], connectionKillSwitch.flow[ByteString]) atop
        TLSPlacebo().reversed atop
        Http().clientLayer(Host("example.org")).reversed mapMaterializedValue (_ ⇒ Future.successful(newOutgoingConnection()))
  }

  class KillSwitchedClientTransport(connectionKillSwitch: SharedKillSwitch) extends ClientTransport {
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
      Flow[ByteString]
        .via(connectionKillSwitch.flow[ByteString])
        .viaMat(ClientTransport.TCP.connectTo(host, port, settings))(Keep.right)
        .viaMat(connectionKillSwitch.flow[ByteString])(Keep.left)
  }

  /** Transport that uses actual top-level Http APIs to establish a plaintext HTTP connection */
  case object AkkaHttpEngineTCP extends TopLevelApiClientServerImplementation {
    protected override def bindServerSource = Http().bind("localhost", 0)
    protected def clientConnectionFlow(connectionKillSwitch: SharedKillSwitch): Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnectionUsingTransport(host = "localhost", port = serverBinding.localAddress.getPort, connectionContext = ConnectionContext.noEncryption(), transport = new KillSwitchedClientTransport(connectionKillSwitch))
  }

  /**
   * Transport that uses actual top-level Http APIs to establish a HTTPS connection
   *
   * Currently requires an /etc/hosts entry that points akka.example.org to a locally bindable address.
   */
  case object AkkaHttpEngineTLS extends TopLevelApiClientServerImplementation {
    protected override def bindServerSource = Http().bind("akka.example.org", 0, connectionContext = ExampleHttpContexts.exampleServerContext)
    protected def clientConnectionFlow(connectionKillSwitch: SharedKillSwitch): Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnectionUsingTransport(host = "akka.example.org", port = serverBinding.localAddress.getPort, connectionContext = ExampleHttpContexts.exampleClientContext, transport = new KillSwitchedClientTransport(connectionKillSwitch))
  }
  abstract class TopLevelApiClientServerImplementation extends ClientServerImplementation {
    def failsHandlerInputWhenHandlerOutputFails: Boolean = false

    protected def bindServerSource: Source[Http.IncomingConnection, Future[ServerBinding]]
    protected def clientConnectionFlow(connectionKillSwitch: SharedKillSwitch): Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]

    val connectionProbe = TestProbe()
    val serverBinding: ServerBinding =
      bindServerSource
        .to(Sink.foreach { serverConnection ⇒
          connectionProbe.ref ! serverConnection
        })
        .run().awaitResult(3.seconds)

    override def get(connectionKillSwitch: SharedKillSwitch): BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]] =
      // needs to be an involved two step process:
      //   1. setup client flow and proxies on the server side to be able to return that flow immediately
      //   2. when client connection was established, grab server connection as well and attach to proxies
      //      (cannot be implemented with just mapMaterializedValue because there's no transposing constructor for BidiFlow)
      BidiFlow.fromGraph(
        GraphDSL.create(Sink.asPublisher[HttpResponse](fanout = false), Source.asSubscriber[HttpRequest], clientConnectionFlow(connectionKillSwitch))((_, _, _)) { implicit builder ⇒ (resIn, reqOut, client) ⇒
          import GraphDSL.Implicits._

          builder.materializedValue ~> Sink.foreach[(Publisher[HttpResponse], Subscriber[HttpRequest], Future[Http.OutgoingConnection])] {
            case (resOut, reqIn, clientConn) ⇒
              clientConn.foreach { _ ⇒
                val serverConn = connectionProbe.expectMsgType[Http.IncomingConnection]
                Flow.fromSinkAndSource(
                  Sink.fromSubscriber(reqIn),
                  Source.fromPublisher(resOut)).join(serverConn.flow).run()
              }(system.dispatcher)
          }

          BidiShape(resIn.in, client.out, client.in, reqOut.out)
        }
      ).mapMaterializedValue(_._3)
  }

  /** Generates a new unique outgoingConnection */
  protected val newOutgoingConnection: () ⇒ Http.OutgoingConnection = {
    val portCounter = new AtomicInteger(1)

    () ⇒ {
      val connId = portCounter.getAndIncrement()
      Http.OutgoingConnection(
        InetSocketAddress.createUnresolved(s"local-$connId", connId % 65536),
        InetSocketAddress.createUnresolved("remote", 5555))
    }
  }
}
