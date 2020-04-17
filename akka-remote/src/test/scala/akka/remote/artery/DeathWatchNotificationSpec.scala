/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._

import akka.actor._
import akka.testkit._
import com.typesafe.config.ConfigFactory

object DeathWatchNotificationSpec {

  val config = ConfigFactory.parseString(s"""
    akka {
        loglevel = DEBUG
        actor {
            provider = remote
        }
        remote.use-unsafe-remote-features-outside-cluster = on
    }
    """).withFallback(ArterySpecSupport.defaultConfig)

  object Sender {
    def props(receiver: ActorRef, sendOnStop: Vector[String]): Props =
      Props(new Sender(receiver, sendOnStop))
  }

  class Sender(receiver: ActorRef, sendOnStop: Vector[String]) extends Actor {
    override def receive: Receive = {
      case msg => sender() ! msg
    }

    override def postStop(): Unit = {
      sendOnStop.foreach(receiver ! _)
    }
  }
}

class DeathWatchNotificationSpec extends ArteryMultiNodeSpec(DeathWatchNotificationSpec.config) with ImplicitSender {
  import DeathWatchNotificationSpec.Sender

  private val otherSystem = newRemoteSystem(name = Some("other"))

  private val messages = (1 to 100).map(_.toString).toVector

  private def setupSender(receiverProbe: TestProbe, name: String): Unit = {
    val receiverPath = receiverProbe.ref.path.toStringWithAddress(address(system))
    val otherProbe = TestProbe()(otherSystem)
    otherSystem.actorSelection(receiverPath).tell(Identify(None), otherProbe.ref)
    val receiver = otherProbe.expectMsgType[ActorIdentity](5.seconds).ref.get
    receiver.path.address.hasGlobalScope should ===(true) // should be remote
    otherSystem.actorOf(Sender.props(receiver, messages), name)
  }

  private def identifySender(name: String): ActorRef = {
    system.actorSelection(rootActorPath(otherSystem) / "user" / name) ! Identify(None)
    val sender = expectMsgType[ActorIdentity](5.seconds).ref.get
    sender
  }

  "receive Terminated after ordinary messages" in {
    val receiverProbe = TestProbe()
    setupSender(receiverProbe, "sender")
    val sender = identifySender("sender")

    receiverProbe.watch(sender)
    // make it likely that the watch has been established
    sender.tell("echo", receiverProbe.ref)
    receiverProbe.expectMsg("echo")

    sender ! PoisonPill
    receiverProbe.receiveN(messages.size).toVector shouldBe messages
    receiverProbe.expectTerminated(sender)
  }

  "receive Terminated after ordinary messages when system is shutdown" in {
    val receiverProbe1 = TestProbe()
    setupSender(receiverProbe1, "sender1")
    val sender1 = identifySender("sender1")

    val receiverProbe2 = TestProbe()
    setupSender(receiverProbe2, "sender2")
    val sender2 = identifySender("sender2")

    val receiverProbe3 = TestProbe()
    setupSender(receiverProbe3, "sender3")
    val sender3 = identifySender("sender3")

    receiverProbe1.watch(sender1)
    receiverProbe2.watch(sender2)
    receiverProbe3.watch(sender3)
    // make it likely that the watch has been established
    sender1.tell("echo1", receiverProbe1.ref)
    receiverProbe1.expectMsg("echo1")
    sender2.tell("echo2", receiverProbe2.ref)
    receiverProbe2.expectMsg("echo2")
    sender3.tell("echo3", receiverProbe3.ref)
    receiverProbe3.expectMsg("echo3")

    otherSystem.terminate()
    receiverProbe1.receiveN(messages.size, 5.seconds).toVector shouldBe messages
    receiverProbe1.expectTerminated(sender1)
    receiverProbe2.receiveN(messages.size).toVector shouldBe messages
    receiverProbe2.expectTerminated(sender2)
    receiverProbe3.receiveN(messages.size).toVector shouldBe messages
    receiverProbe3.expectTerminated(sender3)
  }

}
