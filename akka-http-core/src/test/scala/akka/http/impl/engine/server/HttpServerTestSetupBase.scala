/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.server

import akka.stream.io.{ SendBytes, SslTlsOutbound, SessionBytes }

import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.util.ByteString

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source, FlowGraph }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }

import akka.http.impl.util._

import akka.http.ServerSettings
import akka.http.scaladsl.model.headers.{ ProductVersion, Server }
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }

abstract class HttpServerTestSetupBase {
  implicit def system: ActorSystem
  implicit def materializer: FlowMaterializer

  val requests = TestSubscriber.manualProbe[HttpRequest]
  val responses = TestPublisher.manualProbe[HttpResponse]

  def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))

  val (netIn, netOut) = {
    val netIn = TestPublisher.manualProbe[ByteString]
    val netOut = TestSubscriber.manualProbe[ByteString]

    FlowGraph.closed(HttpServerBluePrint(settings, NoLogging)) { implicit b ⇒
      server ⇒
        import FlowGraph.Implicits._
        Source(netIn) ~> Flow[ByteString].map(SessionBytes(null, _)) ~> server.in2
        server.out1 ~> Flow[SslTlsOutbound].collect { case SendBytes(x) ⇒ x } ~> Sink(netOut)
        server.out2 ~> Sink(requests)
        Source(responses) ~> server.in1
    }.run()

    netIn -> netOut
  }

  def wipeDate(string: String) =
    string.fastSplit('\n').map {
      case s if s.startsWith("Date:") ⇒ "Date: XXXX\r"
      case s                          ⇒ s
    }.mkString("\n")

  val netInSub = netIn.expectSubscription()
  val netOutSub = netOut.expectSubscription()
  val requestsSub = requests.expectSubscription()
  val responsesSub = responses.expectSubscription()

  def expectRequest: HttpRequest = {
    requestsSub.request(1)
    requests.expectNext()
  }
  def expectNoRequest(max: FiniteDuration): Unit = requests.expectNoMsg(max)
  def expectNetworkClose(): Unit = netOut.expectComplete()

  def send(data: ByteString): Unit = netInSub.sendNext(data)
  def send(data: String): Unit = send(ByteString(data, "UTF8"))

  def closeNetworkInput(): Unit = netInSub.sendComplete()
}