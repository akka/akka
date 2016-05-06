/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

import akka.Done
import akka.NotUsed
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.InternalActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.RootActorPath
import akka.actor.Stash
import akka.remote.EndpointManager.Send
import akka.remote.RemoteActorRef
import akka.remote.artery.SystemMessageDelivery._
import akka.remote.artery.Transport.InboundEnvelope
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.stage.AsyncCallback
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.SocketUtil
import akka.testkit.TestActors
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

object SystemMessageDeliverySpec {

  val Seq(portA, portB) = SocketUtil.temporaryServerAddresses(2, "localhost", udp = true).map(_.getPort)

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = $portA
     }
     akka.actor.serialize-creators = off
     akka.actor.serialize-messages = off
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.port = $portB")
    .withFallback(commonConfig)

  class TestReplyJunction(sendCallbackTo: ActorRef) extends SystemMessageReplyJunction.Junction {

    def addReplyInterest(filter: InboundEnvelope ⇒ Boolean, replyCallback: AsyncCallback[SystemMessageReply]): Future[Done] = {
      sendCallbackTo ! replyCallback
      Future.successful(Done)
    }

    override def removeReplyInterest(callback: AsyncCallback[SystemMessageReply]): Unit = ()

    override def stopped: Future[Done] = Promise[Done]().future
  }

  def replyConnectorProps(dropRate: Double): Props =
    Props(new ReplyConnector(dropRate))

  class ReplyConnector(dropRate: Double) extends Actor with Stash {
    override def receive = {
      case callback: AsyncCallback[SystemMessageReply] @unchecked ⇒
        context.become(active(callback))
        unstashAll()
      case _ ⇒ stash()
    }

    def active(callback: AsyncCallback[SystemMessageReply]): Receive = {
      case reply: SystemMessageReply ⇒
        if (ThreadLocalRandom.current().nextDouble() >= dropRate)
          callback.invoke(reply)
    }
  }

}

class SystemMessageDeliverySpec extends AkkaSpec(SystemMessageDeliverySpec.commonConfig) with ImplicitSender {
  import SystemMessageDeliverySpec._

  val addressA = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val systemB = ActorSystem("systemB", configB)
  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val rootB = RootActorPath(addressB)
  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  override def afterTermination(): Unit = shutdown(systemB)

  def setupManualCallback(ackRecipient: ActorRef, resendInterval: FiniteDuration,
                          dropSeqNumbers: Vector[Long], sendCount: Int): (TestSubscriber.Probe[String], AsyncCallback[SystemMessageReply]) = {
    val callbackProbe = TestProbe()
    val replyJunction = new TestReplyJunction(callbackProbe.ref)

    val sink =
      send(sendCount, resendInterval, replyJunction, ackRecipient)
        .via(drop(dropSeqNumbers))
        .via(inbound)
        .map(_.message.asInstanceOf[String])
        .runWith(TestSink.probe)

    val callback = callbackProbe.expectMsgType[AsyncCallback[SystemMessageReply]]
    (sink, callback)
  }

  def send(sendCount: Int, resendInterval: FiniteDuration, replyJunction: SystemMessageReplyJunction.Junction,
           ackRecipient: ActorRef): Source[Send, NotUsed] = {
    val remoteRef = null.asInstanceOf[RemoteActorRef] // not used
    Source(1 to sendCount)
      .map(n ⇒ Send("msg-" + n, None, remoteRef, None))
      .via(new SystemMessageDelivery(replyJunction, resendInterval, addressA, addressB, ackRecipient))
  }

  def inbound: Flow[Send, InboundEnvelope, NotUsed] = {
    val recipient = null.asInstanceOf[InternalActorRef] // not used
    Flow[Send]
      .map {
        case Send(sysEnv: SystemMessageEnvelope, _, _, _) ⇒
          InboundEnvelope(recipient, addressB, sysEnv, None)
      }
      .async
      .via(new SystemMessageAcker(addressB))
  }

  def drop(dropSeqNumbers: Vector[Long]): Flow[Send, Send, NotUsed] = {
    Flow[Send]
      .statefulMapConcat(() ⇒ {
        var dropping = dropSeqNumbers

        {
          case s @ Send(SystemMessageEnvelope(_, seqNo, _), _, _, _) ⇒
            val i = dropping.indexOf(seqNo)
            if (i >= 0) {
              dropping = dropping.updated(i, -1L)
              Nil
            } else
              List(s)
        }
      })
  }

  def randomDrop[T](dropRate: Double): Flow[T, T, NotUsed] = Flow[T].mapConcat { elem ⇒
    if (ThreadLocalRandom.current().nextDouble() < dropRate) Nil
    else List(elem)
  }

  "System messages" must {

    "be delivered with real actors" in {
      val actorOnSystemB = systemB.actorOf(TestActors.echoActorProps, "echo")

      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      watch(remoteRef)
      remoteRef ! PoisonPill
      expectTerminated(remoteRef)
    }

    "be resent when some in the middle are lost" in {
      val ackRecipient = TestProbe()
      val (sink, replyCallback) =
        setupManualCallback(ackRecipient.ref, resendInterval = 60.seconds, dropSeqNumbers = Vector(3L, 4L), sendCount = 5)

      sink.request(100)
      sink.expectNext("msg-1")
      sink.expectNext("msg-2")
      ackRecipient.expectMsg(Ack(1L, addressB))
      ackRecipient.expectMsg(Ack(2L, addressB))
      // 3 and 4 was dropped
      ackRecipient.expectMsg(Nack(2L, addressB))
      sink.expectNoMsg(100.millis) // 3 was dropped
      replyCallback.invoke(Nack(2L, addressB))
      // resending 3, 4, 5
      sink.expectNext("msg-3")
      ackRecipient.expectMsg(Ack(3L, addressB))
      sink.expectNext("msg-4")
      ackRecipient.expectMsg(Ack(4L, addressB))
      sink.expectNext("msg-5")
      ackRecipient.expectMsg(Ack(5L, addressB))
      ackRecipient.expectNoMsg(100.millis)
      replyCallback.invoke(Ack(5L, addressB))
      sink.expectComplete()
    }

    "be resent when first is lost" in {
      val ackRecipient = TestProbe()
      val (sink, replyCallback) =
        setupManualCallback(ackRecipient.ref, resendInterval = 60.seconds, dropSeqNumbers = Vector(1L), sendCount = 3)

      sink.request(100)
      ackRecipient.expectMsg(Nack(0L, addressB)) // from receiving 2
      ackRecipient.expectMsg(Nack(0L, addressB)) // from receiving 3
      sink.expectNoMsg(100.millis) // 1 was dropped
      replyCallback.invoke(Nack(0L, addressB))
      replyCallback.invoke(Nack(0L, addressB))
      // resending 1, 2, 3
      sink.expectNext("msg-1")
      ackRecipient.expectMsg(Ack(1L, addressB))
      sink.expectNext("msg-2")
      ackRecipient.expectMsg(Ack(2L, addressB))
      sink.expectNext("msg-3")
      ackRecipient.expectMsg(Ack(3L, addressB))
      replyCallback.invoke(Ack(3L, addressB))
      sink.expectComplete()
    }

    "be resent when last is lost" in {
      val ackRecipient = TestProbe()
      val (sink, replyCallback) =
        setupManualCallback(ackRecipient.ref, resendInterval = 1.second, dropSeqNumbers = Vector(3L), sendCount = 3)

      sink.request(100)
      sink.expectNext("msg-1")
      ackRecipient.expectMsg(Ack(1L, addressB))
      replyCallback.invoke(Ack(1L, addressB))
      sink.expectNext("msg-2")
      ackRecipient.expectMsg(Ack(2L, addressB))
      replyCallback.invoke(Ack(2L, addressB))
      sink.expectNoMsg(200.millis) // 3 was dropped
      // resending 3 due to timeout
      sink.expectNext("msg-3")
      ackRecipient.expectMsg(Ack(3L, addressB))
      replyCallback.invoke(Ack(3L, addressB))
      sink.expectComplete()
    }

    "deliver all during stress and random dropping" in {
      val N = 10000
      val dropRate = 0.1
      val replyConnector = system.actorOf(replyConnectorProps(dropRate))
      val replyJunction = new TestReplyJunction(replyConnector)

      val output =
        send(N, 1.second, replyJunction, replyConnector)
          .via(randomDrop(dropRate))
          .via(inbound)
          .map(_.message.asInstanceOf[String])
          .runWith(Sink.seq)

      Await.result(output, 20.seconds) should ===((1 to N).map("msg-" + _).toVector)
    }

    "deliver all during throttling and random dropping" in {
      val N = 500
      val dropRate = 0.1
      val replyConnector = system.actorOf(replyConnectorProps(dropRate))
      val replyJunction = new TestReplyJunction(replyConnector)

      val output =
        send(N, 1.second, replyJunction, replyConnector)
          .throttle(200, 1.second, 10, ThrottleMode.shaping)
          .via(randomDrop(dropRate))
          .via(inbound)
          .map(_.message.asInstanceOf[String])
          .runWith(Sink.seq)

      Await.result(output, 20.seconds) should ===((1 to N).map("msg-" + _).toVector)
    }

  }

}
