/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.NotUsed
import akka.actor.ActorIdentity
import akka.actor.ActorSystem
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.RootActorPath
import akka.remote.{ AddressUidExtension, RARP, UniqueAddress }
import akka.remote.artery.SystemMessageDelivery._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import akka.util.OptionVal

object SystemMessageDeliverySpec {

  case class TestSysMsg(s: String) extends SystemMessageDelivery.AckedDeliveryMessage

}

class SystemMessageDeliverySpec extends ArteryMultiNodeSpec(ArterySpecSupport.defaultConfig) with ImplicitSender {
  import SystemMessageDeliverySpec._

  val addressA = UniqueAddress(
    address(system),
    AddressUidExtension(system).longAddressUid)
  val systemB = newRemoteSystem(name = Some("systemB"))
  val addressB = UniqueAddress(
    address(systemB),
    AddressUidExtension(systemB).longAddressUid)
  val rootB = RootActorPath(addressB.address)
  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  private val outboundEnvelopePool = ReusableOutboundEnvelope.createObjectPool(capacity = 16)

  private def send(sendCount: Int, resendInterval: FiniteDuration, outboundContext: OutboundContext): Source[OutboundEnvelope, NotUsed] = {
    val deadLetters = TestProbe().ref
    Source(1 to sendCount)
      .map(n ⇒ outboundEnvelopePool.acquire().init(OptionVal.None, TestSysMsg("msg-" + n), OptionVal.None))
      .via(new SystemMessageDelivery(outboundContext, deadLetters, resendInterval, maxBufferSize = 1000))
  }

  private def inbound(inboundContext: InboundContext): Flow[OutboundEnvelope, InboundEnvelope, NotUsed] = {
    val recipient = OptionVal.None // not used
    Flow[OutboundEnvelope]
      .map(outboundEnvelope ⇒ outboundEnvelope.message match {
        case sysEnv: SystemMessageEnvelope ⇒
          InboundEnvelope(recipient, sysEnv, OptionVal.None, addressA.uid,
            inboundContext.association(addressA.uid))
      })
      .async
      .via(new SystemMessageAcker(inboundContext))
  }

  private def drop(dropSeqNumbers: Vector[Long]): Flow[OutboundEnvelope, OutboundEnvelope, NotUsed] = {
    Flow[OutboundEnvelope]
      .statefulMapConcat(() ⇒ {
        var dropping = dropSeqNumbers

        {
          outboundEnvelope ⇒
            outboundEnvelope.message match {
              case SystemMessageEnvelope(_, seqNo, _) ⇒
                val i = dropping.indexOf(seqNo)
                if (i >= 0) {
                  dropping = dropping.updated(i, -1L)
                  Nil
                } else
                  List(outboundEnvelope)
              case _ ⇒ Nil
            }
        }
      })
  }

  private def randomDrop[T](dropRate: Double): Flow[T, T, NotUsed] = Flow[T].mapConcat { elem ⇒
    if (ThreadLocalRandom.current().nextDouble() < dropRate) Nil
    else List(elem)
  }

  "System messages" must {

    "be delivered with real actors" in {
      systemB.actorOf(TestActors.echoActorProps, "echo")

      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      watch(remoteRef)
      remoteRef ! PoisonPill
      expectTerminated(remoteRef)
    }

    "be flushed on shutdown" in {
      val systemC = ActorSystem("systemC", system.settings.config)
      try {
        systemC.actorOf(TestActors.echoActorProps, "echo")

        val addressC = RARP(systemC).provider.getDefaultAddress
        val rootC = RootActorPath(addressC)

        val remoteRef = {
          system.actorSelection(rootC / "user" / "echo") ! Identify(None)
          expectMsgType[ActorIdentity].ref.get
        }

        watch(remoteRef)
        remoteRef ! "hello"
        expectMsg("hello")
        systemC.terminate()
        // DeathWatchNotification is sent from systemC, failure detection takes longer than 3 seconds
        expectTerminated(remoteRef, 5.seconds)
      } finally {
        shutdown(systemC)
      }
    }

    "be resent when some in the middle are lost" in {
      val replyProbe = TestProbe()
      val controlSubject = new TestControlMessageSubject
      val inboundContextB = new ManualReplyInboundContext(replyProbe.ref, addressB, controlSubject)
      val inboundContextA = new TestInboundContext(addressB, controlSubject)
      val outboundContextA = inboundContextA.association(addressB.address)

      val sink = send(sendCount = 5, resendInterval = 60.seconds, outboundContextA)
        .via(drop(dropSeqNumbers = Vector(3L, 4L)))
        .via(inbound(inboundContextB))
        .map(_.message.asInstanceOf[TestSysMsg])
        .runWith(TestSink.probe)

      sink.request(100)
      sink.expectNext(TestSysMsg("msg-1"))
      sink.expectNext(TestSysMsg("msg-2"))
      replyProbe.expectMsg(Ack(1L, addressB))
      replyProbe.expectMsg(Ack(2L, addressB))
      // 3 and 4 was dropped
      replyProbe.expectMsg(Nack(2L, addressB))
      sink.expectNoMsg(100.millis) // 3 was dropped
      inboundContextB.deliverLastReply()
      // resending 3, 4, 5
      sink.expectNext(TestSysMsg("msg-3"))
      replyProbe.expectMsg(Ack(3L, addressB))
      sink.expectNext(TestSysMsg("msg-4"))
      replyProbe.expectMsg(Ack(4L, addressB))
      sink.expectNext(TestSysMsg("msg-5"))
      replyProbe.expectMsg(Ack(5L, addressB))
      replyProbe.expectNoMsg(100.millis)
      inboundContextB.deliverLastReply()
      sink.expectComplete()
    }

    "be resent when first is lost" in {
      val replyProbe = TestProbe()
      val controlSubject = new TestControlMessageSubject
      val inboundContextB = new ManualReplyInboundContext(replyProbe.ref, addressB, controlSubject)
      val inboundContextA = new TestInboundContext(addressB, controlSubject)
      val outboundContextA = inboundContextA.association(addressB.address)

      val sink = send(sendCount = 3, resendInterval = 60.seconds, outboundContextA)
        .via(drop(dropSeqNumbers = Vector(1L)))
        .via(inbound(inboundContextB))
        .map(_.message.asInstanceOf[TestSysMsg])
        .runWith(TestSink.probe)

      sink.request(100)
      replyProbe.expectMsg(Nack(0L, addressB)) // from receiving 2
      replyProbe.expectMsg(Nack(0L, addressB)) // from receiving 3
      sink.expectNoMsg(100.millis) // 1 was dropped
      inboundContextB.deliverLastReply() // it's ok to not delivery all nacks
      // resending 1, 2, 3
      sink.expectNext(TestSysMsg("msg-1"))
      replyProbe.expectMsg(Ack(1L, addressB))
      sink.expectNext(TestSysMsg("msg-2"))
      replyProbe.expectMsg(Ack(2L, addressB))
      sink.expectNext(TestSysMsg("msg-3"))
      replyProbe.expectMsg(Ack(3L, addressB))
      inboundContextB.deliverLastReply()
      sink.expectComplete()
    }

    "be resent when last is lost" in {
      val replyProbe = TestProbe()
      val controlSubject = new TestControlMessageSubject
      val inboundContextB = new ManualReplyInboundContext(replyProbe.ref, addressB, controlSubject)
      val inboundContextA = new TestInboundContext(addressB, controlSubject)
      val outboundContextA = inboundContextA.association(addressB.address)

      val sink = send(sendCount = 3, resendInterval = 2.seconds, outboundContextA)
        .via(drop(dropSeqNumbers = Vector(3L)))
        .via(inbound(inboundContextB))
        .map(_.message.asInstanceOf[TestSysMsg])
        .runWith(TestSink.probe)

      sink.request(100)
      sink.expectNext(TestSysMsg("msg-1"))
      replyProbe.expectMsg(Ack(1L, addressB))
      inboundContextB.deliverLastReply()
      sink.expectNext(TestSysMsg("msg-2"))
      replyProbe.expectMsg(Ack(2L, addressB))
      inboundContextB.deliverLastReply()
      sink.expectNoMsg(200.millis) // 3 was dropped
      // resending 3 due to timeout
      sink.expectNext(TestSysMsg("msg-3"))
      replyProbe.expectMsg(4.seconds, Ack(3L, addressB))
      // continue resending
      replyProbe.expectMsg(4.seconds, Ack(3L, addressB))
      inboundContextB.deliverLastReply()
      replyProbe.expectNoMsg(2200.millis)
      sink.expectComplete()
    }

    "deliver all during stress and random dropping" in {
      val N = 10000
      val dropRate = 0.1
      val controlSubject = new TestControlMessageSubject
      val inboundContextB = new TestInboundContext(addressB, controlSubject, replyDropRate = dropRate)
      val inboundContextA = new TestInboundContext(addressB, controlSubject)
      val outboundContextA = inboundContextA.association(addressB.address)

      val output =
        send(N, 1.second, outboundContextA)
          .via(randomDrop(dropRate))
          .via(inbound(inboundContextB))
          .map(_.message.asInstanceOf[TestSysMsg])
          .runWith(Sink.seq)

      Await.result(output, 20.seconds) should ===((1 to N).map(n ⇒ TestSysMsg("msg-" + n)).toVector)
    }

    "deliver all during throttling and random dropping" in {
      val N = 500
      val dropRate = 0.1
      val controlSubject = new TestControlMessageSubject
      val inboundContextB = new TestInboundContext(addressB, controlSubject, replyDropRate = dropRate)
      val inboundContextA = new TestInboundContext(addressB, controlSubject)
      val outboundContextA = inboundContextA.association(addressB.address)

      val output =
        send(N, 1.second, outboundContextA)
          .throttle(200, 1.second, 10, ThrottleMode.shaping)
          .via(randomDrop(dropRate))
          .via(inbound(inboundContextB))
          .map(_.message.asInstanceOf[TestSysMsg])
          .runWith(Sink.seq)

      Await.result(output, 20.seconds) should ===((1 to N).map(n ⇒ TestSysMsg("msg-" + n)).toVector)
    }

  }

}
