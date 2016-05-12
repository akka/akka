/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeoutException

import scala.concurrent.duration._

import akka.actor.Address
import akka.actor.InternalActorRef
import akka.remote.EndpointManager.Send
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.OutboundHandshake.HandshakeReq
import akka.remote.artery.OutboundHandshake.HandshakeRsp
import akka.remote.artery.SystemMessageDelivery._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Keep
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

class OutboundHandshakeSpec extends AkkaSpec with ImplicitSender {

  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  val addressA = UniqueAddress(Address("akka.artery", "sysA", "hostA", 1001), 1)
  val addressB = UniqueAddress(Address("akka.artery", "sysB", "hostB", 1002), 2)

  private def setupStream(outboundContext: OutboundContext, timeout: FiniteDuration = 5.seconds): (TestPublisher.Probe[String], TestSubscriber.Probe[Any]) = {
    val destination = null.asInstanceOf[RemoteActorRef] // not used
    TestSource.probe[String]
      .map(msg ⇒ Send(msg, None, destination, None))
      .via(new OutboundHandshake(outboundContext, timeout))
      .map { case Send(msg, _, _, _) ⇒ msg }
      .toMat(TestSink.probe[Any])(Keep.both)
      .run()
  }

  "OutboundHandshake stage" must {
    "send HandshakeReq when first pulled" in {
      val inboundContext = new TestInboundContext(localAddress = addressA)
      val outboundContext = inboundContext.association(addressB.address)
      val (upstream, downstream) = setupStream(outboundContext)

      downstream.request(10)
      downstream.expectNext(HandshakeReq(addressA))
      downstream.cancel()
    }

    "timeout if not receiving HandshakeRsp" in {
      val inboundContext = new TestInboundContext(localAddress = addressA)
      val outboundContext = inboundContext.association(addressB.address)
      val (upstream, downstream) = setupStream(outboundContext, timeout = 200.millis)

      downstream.request(1)
      downstream.expectNext(HandshakeReq(addressA))
      downstream.expectError().getClass should be(classOf[TimeoutException])
    }

    "not deliver messages from upstream until handshake completed" in {
      val controlSubject = new TestControlMessageSubject
      val inboundContext = new TestInboundContext(localAddress = addressA, controlSubject)
      val outboundContext = inboundContext.association(addressB.address)
      val recipient = null.asInstanceOf[InternalActorRef] // not used
      val (upstream, downstream) = setupStream(outboundContext)

      downstream.request(10)
      downstream.expectNext(HandshakeReq(addressA))
      upstream.sendNext("msg1")
      downstream.expectNoMsg(200.millis)
      controlSubject.sendControl(InboundEnvelope(recipient, addressA.address, HandshakeRsp(addressB), None))
      downstream.expectNext("msg1")
      upstream.sendNext("msg2")
      downstream.expectNext("msg2")
      downstream.cancel()
    }

    "complete handshake via another sub-channel" in {
      val inboundContext = new TestInboundContext(localAddress = addressA)
      val outboundContext = inboundContext.association(addressB.address)
      val (upstream, downstream) = setupStream(outboundContext)

      downstream.request(10)
      downstream.expectNext(HandshakeReq(addressA))
      upstream.sendNext("msg1")
      // handshake completed first by another sub-channel
      outboundContext.completeRemoteAddress(addressB)
      downstream.expectNext("msg1")
      upstream.sendNext("msg2")
      downstream.expectNext("msg2")
      downstream.cancel()
    }

  }

}
