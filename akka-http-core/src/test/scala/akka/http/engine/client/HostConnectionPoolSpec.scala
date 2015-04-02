/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import scala.util.{ Success, Try }
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.scaladsl._
import akka.http.{ Http, TestUtils }
import akka.http.model.headers._
import akka.http.model._

class HostConnectionPoolSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF") {
  implicit val materializer = ActorFlowMaterializer()

  "The host-level client infrastructure" should {

    "properly complete a simple request/response cycle" in new TestSetup {
      val (requestIn, responseOut, hcp) = cachedHostConnectionPool[Int]()

      val reqSub = requestIn.expectSubscription()
      reqSub.expectRequest(4)
      reqSub.sendNext(HttpRequest(uri = "/") -> 42)

      val respSub = responseOut.expectSubscription()
      respSub.request(1)
      val (Success(response), 42) = responseOut.expectNext()
      response.headers should contain(RawHeader("Req-Host", s"localhost:$serverPort"))
    }

    "add default headers to requests if they don't contain them" in new TestSetup {
      val (requestIn, responseOut, hcp) =
        cachedHostConnectionPool[Int](defaultHeaders = List(RawHeader("X-Custom-Header", "Default")))

      val reqSub = requestIn.expectSubscription()
      reqSub.expectRequest(4)
      reqSub.sendNext(HttpRequest(uri = "/") -> 42)

      val respSub = responseOut.expectSubscription()
      respSub.request(1)
      val (Success(response), 42) = responseOut.expectNext()
      response.headers should contain(RawHeader("Req-X-Custom-Header", "Default"))
    }
  }

  class TestSetup {
    val (serverHostName, serverPort) = TestUtils.temporaryServerHostnameAndPort()

    { // bind test server which simply echos incoming requests
      val handler: HttpRequest ⇒ HttpResponse = {
        case HttpRequest(_, uri, headers, entity, _) ⇒
          val responseHeaders = RawHeader("Req-Uri", uri.toString) +: headers.map(h ⇒ RawHeader("Req-" + h.name, h.value))
          HttpResponse(headers = responseHeaders, entity = entity)
      }
      Http().bindAndHandleSync(handler, serverHostName, serverPort)
    }

    def cachedHostConnectionPool[T](settings: Option[HostConnectionPoolSettings] = None,
                                    defaultHeaders: List[HttpHeader] = Nil) = {
      val requestIn = StreamTestKit.PublisherProbe[(HttpRequest, T)]
      val responseOut = StreamTestKit.SubscriberProbe[(Try[HttpResponse], T)]
      val poolFlow = Http().cachedHostConnectionPool[T](serverHostName, serverPort, Nil, settings, defaultHeaders)
      val hcp = Source(requestIn).viaMat(poolFlow)(Keep.right).toMat(Sink(responseOut))(Keep.left).run()
      (requestIn, responseOut, hcp)
    }

  }
}