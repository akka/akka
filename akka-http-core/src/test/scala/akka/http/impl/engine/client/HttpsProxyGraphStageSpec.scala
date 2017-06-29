/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.impl.util.EnhancedString
import akka.http.scaladsl.model.headers.{ BasicHttpCredentials, HttpCredentials, `Proxy-Authorization` }
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.testkit.{ TestPublisher, TestSubscriber, Utils }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.AkkaSpec
import akka.util.ByteString
import scala.concurrent.duration._

class HttpsProxyGraphStageSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withFuzzing(true))

  "A ProxyGraphStage" should {
    "send CONNECT message and then forward incoming messages" in new Context {
      testCase() { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(1)

        val connectMsg = flowInProbe.requestNext()
        connectMsg.utf8String should startWith(s"CONNECT $targetHostName:$targetPort HTTP/1.1")
        flowOutProbe.sendNext(ByteString("HTTP/1.0 200 Connection established\r\n\r\n"))

        // we deliberately wait here some time to see if ProxyGraphStage properly handles slow demand from Https Proxy
        flowInProbe.expectNoMsg(300.millis)

        source.sendNext(ByteString("something"))
        flowInProbe.requestNext(ByteString("anything"))
        flowOutProbe.sendNext(ByteString("anything"))
        sink.expectNext(ByteString("anything"))

        flowInProbe.requestNext(ByteString("something"))
        flowOutProbe.sendNext(ByteString("something"))
        sink.requestNext(ByteString("something"))

        source.sendComplete()
        flowOutProbe.sendComplete()
        sink.expectComplete()
      }
    }

    "send CONNECT message with ProxyAuth header and then forward incoming messages" in new Context {
      testCase(Some(basicProxyAuth)) { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(1)

        val connectMsg = flowInProbe.requestNext()
        connectMsg.utf8String should startWith(
          new EnhancedString(s"""CONNECT $targetHostName:$targetPort HTTP/1.1
            |Host: $targetHostName
            |Proxy-Authorization: Basic dGVzdFVzZXJuYW1lOnRlc3RQYXNzd29yZA==
            |
            |""").stripMarginWithNewline("\r\n"))
        flowOutProbe.sendNext(ByteString("HTTP/1.0 200 Connection established\r\n\r\n"))

        // we deliberately wait here some time to see if ProxyGraphStage properly handles slow demand from Https Proxy
        flowInProbe.expectNoMsg(300.millis)

        source.sendNext(ByteString("something"))
        flowInProbe.requestNext(ByteString("anything"))
        flowOutProbe.sendNext(ByteString("anything"))
        sink.expectNext(ByteString("anything"))

        flowInProbe.requestNext(ByteString("something"))
        flowOutProbe.sendNext(ByteString("something"))
        sink.requestNext(ByteString("something"))

        source.sendComplete()
        flowOutProbe.sendComplete()
        sink.expectComplete()
      }
    }

    "treat any 2xx response for CONNECT message as successful" in new Context {
      testCase() { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(1)

        flowInProbe.requestNext()
        flowOutProbe.sendNext(ByteString("HTTP/1.0 201 Connection established\r\n\r\n"))

        source.sendComplete()
        flowInProbe.requestNext(ByteString("anything"))
        flowOutProbe.sendComplete()
        sink.expectComplete()
      }
    }

    "treat fragmented 200 response for CONNECT message as successful" in new Context {
      testCase() { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(100)

        flowInProbe.request(100)

        flowInProbe.expectNext()
        flowOutProbe.sendNext(ByteString("HTTP/1.0"))
        flowOutProbe.sendNext(ByteString(" 200"))
        flowOutProbe.sendNext(ByteString(" Connection established\r\n\r\n"))

        val receivedByteString = flowInProbe.expectNext()
        flowOutProbe.sendNext(receivedByteString)

        sink.expectNext(ByteString("anything"))

        source.sendComplete()
        flowOutProbe.sendComplete()
        sink.expectComplete()
      }
    }

    "fail in case of non-2xx Proxy response for CONNECT message" in new Context {
      testCase() { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(100)

        flowInProbe.request(100)

        flowOutProbe.sendNext(ByteString("HTTP/1.0 501 Some Error\r\n\r\n"))

        sink.expectError match {
          case _: ProxyConnectionFailedException ⇒
          case e ⇒
            fail(s"should be ProxyConnectionFailedException, caught ${e.getClass.getName} instead")
        }
      }
    }

    "fail in case of unexpected Proxy response for CONNECT message" in new Context {
      testCase() { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(100)

        flowInProbe.request(100)

        flowOutProbe.sendNext(ByteString("something unexpected"))

        source.expectCancellation()
        sink.expectError()
      }
    }

    "forward additional data sent by Proxy" in new Context {
      testCase() { (source, flowInProbe, flowOutProbe, sink) ⇒
        source.sendNext(ByteString("anything"))
        sink.request(100)

        flowInProbe.request(100)

        flowInProbe.expectNext()
        flowOutProbe.sendNext(ByteString("HTTP/1.0"))
        flowOutProbe.sendNext(ByteString(" 200"))
        flowOutProbe.sendNext(ByteString(" Connection established\r\n"))
        flowOutProbe.sendNext(ByteString("Server: Apache/2.2.22\r\n\r\nsomething"))

        val receivedByteString = flowInProbe.expectNext()
        flowOutProbe.sendNext(receivedByteString)

        sink.expectNext(ByteString("something"))
        sink.expectNext(ByteString("anything"))

        source.sendComplete()
        flowOutProbe.sendComplete()
        sink.expectComplete()
      }
    }

  }

  trait Context {
    val clientSettings = ClientConnectionSettings(system)

    val targetHostName = "akka.io"
    val targetPort = 443
    val basicProxyAuth: BasicHttpCredentials = BasicHttpCredentials("testUsername", "testPassword")

    type PublisherProbe = TestPublisher.Probe[ByteString]
    type SubscriberProbe = TestSubscriber.Probe[ByteString]
    type ProxyAuth = BasicHttpCredentials

    def testCase(proxyAuth: Option[HttpCredentials] = None)(fn: (PublisherProbe, SubscriberProbe, PublisherProbe, SubscriberProbe) ⇒ Unit): Unit = {
      Utils.assertAllStagesStopped {
        val proxyGraphStage = HttpsProxyGraphStage(targetHostName, targetPort, clientSettings, proxyAuth)

        val flowInProbe = TestSubscriber.probe[ByteString]()
        val flowOutProbe = TestPublisher.probe[ByteString]()

        val proxyFlow = Flow.fromSinkAndSource(
          Sink.fromSubscriber(flowInProbe),
          Source.fromPublisher(flowOutProbe))

        val flowUnderTest = proxyGraphStage.join(proxyFlow)

        val (source, sink) = TestSource.probe[ByteString]
          .via(flowUnderTest)
          .toMat(TestSink.probe)(Keep.both)
          .run()

        fn(source, flowInProbe, flowOutProbe, sink)
      }
    }
  }
}
