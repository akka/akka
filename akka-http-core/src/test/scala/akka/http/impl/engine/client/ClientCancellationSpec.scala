package akka.http.impl.engine.client

import javax.net.ssl.SSLContext

import akka.http.scaladsl.{ HttpsContext, Http }
import akka.http.scaladsl.model.{ HttpHeader, HttpResponse, HttpRequest }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit.{ TestSubscriber, TestPublisher, AkkaSpec, TestUtils, Utils }
import akka.http.scaladsl.model.headers

class ClientCancellationSpec extends AkkaSpec("""
    akka.loglevel = DEBUG
    akka.io.tcp.trace-logging = off""") {

  implicit val materializer = ActorMaterializer()
  val noncheckedMaterializer = ActorMaterializer()

  "Http client connections" must {
    val address = TestUtils.temporaryServerAddress()
    Http().bindAndHandleSync(
      { req ⇒ HttpResponse(headers = headers.Connection("close") :: Nil) },
      address.getHostName,
      address.getPort)(noncheckedMaterializer)

    val addressTls = TestUtils.temporaryServerAddress()
    Http().bindAndHandleSync(
      { req ⇒ HttpResponse() }, // TLS client does full-close, no need for the connection:close header
      addressTls.getHostName,
      addressTls.getPort,
      httpsContext = Some(HttpsContext(SSLContext.getDefault)))(noncheckedMaterializer)

    def testCase(connection: Flow[HttpRequest, HttpResponse, Any]): Unit = Utils.assertAllStagesStopped {
      val requests = TestPublisher.probe[HttpRequest]()
      val responses = TestSubscriber.probe[HttpResponse]()
      Source(requests).via(connection).runWith(Sink(responses))
      responses.request(1)
      requests.sendNext(HttpRequest())
      responses.expectNext().entity.dataBytes.runWith(Sink.cancelled)
      responses.cancel()
      requests.expectCancellation()
    }

    "support cancellation in simple outgoing connection" in {
      testCase(
        Http().outgoingConnection(address.getHostName, address.getPort))
    }

    "support cancellation in pooled outgoing connection" in {
      testCase(
        Flow[HttpRequest]
          .map((_, ()))
          .via(Http().cachedHostConnectionPool(address.getHostName, address.getPort)(noncheckedMaterializer))
          .map(_._1.get))
    }

    "support cancellation in simple outgoing connection with TLS" in {
      pending
      testCase(
        Http().outgoingConnectionTls(addressTls.getHostName, addressTls.getPort))
    }

    "support cancellation in pooled outgoing connection with TLS" in {
      pending
      testCase(
        Flow[HttpRequest]
          .map((_, ()))
          .via(Http().cachedHostConnectionPoolTls(addressTls.getHostName, addressTls.getPort)(noncheckedMaterializer))
          .map(_._1.get))
    }

  }

}
