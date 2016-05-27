/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.Address
import akka.actor.InternalActorRef
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
import akka.testkit.TestProbe

object InboundHandshakeSpec {
  case object Control1 extends ControlMessage
  case object Control2 extends ControlMessage
  case object Control3 extends ControlMessage
}

class InboundHandshakeSpec extends AkkaSpec with ImplicitSender {
  import InboundHandshakeSpec._

  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  val addressA = UniqueAddress(Address("artery", "sysA", "hostA", 1001), 1)
  val addressB = UniqueAddress(Address("artery", "sysB", "hostB", 1002), 2)

  private def setupStream(inboundContext: InboundContext, timeout: FiniteDuration = 5.seconds): (TestPublisher.Probe[AnyRef], TestSubscriber.Probe[Any]) = {
    val recipient = null.asInstanceOf[InternalActorRef] // not used
    TestSource.probe[AnyRef]
      .map(msg ⇒ InboundEnvelope(recipient, addressB.address, msg, None, addressA.uid))
      .via(new InboundHandshake(inboundContext, inControlStream = true))
      .map { case InboundEnvelope(_, _, msg, _, _) ⇒ msg }
      .toMat(TestSink.probe[Any])(Keep.both)
      .run()
  }

  "InboundHandshake stage" must {

    "send HandshakeRsp as reply to HandshakeReq" in {
      val replyProbe = TestProbe()
      val inboundContext = new TestInboundContext(addressB, controlProbe = Some(replyProbe.ref))
      val (upstream, downstream) = setupStream(inboundContext)

      downstream.request(10)
      upstream.sendNext(HandshakeReq(addressA))
      upstream.sendNext("msg1")
      replyProbe.expectMsg(HandshakeRsp(addressB))
      downstream.expectNext("msg1")
      downstream.cancel()
    }

    "complete remoteUniqueAddress when receiving HandshakeReq" in {
      val inboundContext = new TestInboundContext(addressB)
      val (upstream, downstream) = setupStream(inboundContext)

      downstream.request(10)
      upstream.sendNext(HandshakeReq(addressA))
      upstream.sendNext("msg1")
      downstream.expectNext("msg1")
      val uniqueRemoteAddress = Await.result(
        inboundContext.association(addressA.address).associationState.uniqueRemoteAddress, remainingOrDefault)
      uniqueRemoteAddress should ===(addressA)
      downstream.cancel()
    }

    "drop message from unknown (receiving system restarted)" in {
      val replyProbe = TestProbe()
      val inboundContext = new TestInboundContext(addressB, controlProbe = Some(replyProbe.ref))
      val (upstream, downstream) = setupStream(inboundContext)

      downstream.request(10)
      // no HandshakeReq
      upstream.sendNext("msg17")
      downstream.expectNoMsg(200.millis) // messages from unknown are dropped

      // and accept messages after handshake
      upstream.sendNext(HandshakeReq(addressA))
      upstream.sendNext("msg18")
      replyProbe.expectMsg(HandshakeRsp(addressB))
      downstream.expectNext("msg18")
      upstream.sendNext("msg19")
      downstream.expectNext("msg19")

      downstream.cancel()
    }

  }

}
