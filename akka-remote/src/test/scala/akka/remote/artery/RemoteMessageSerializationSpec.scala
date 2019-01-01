/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import java.io.NotSerializableException
import java.util.concurrent.ThreadLocalRandom

import akka.actor.{ Actor, ActorRef, PoisonPill, Props }
import akka.remote.{ AssociationErrorEvent, DisassociatedEvent, OversizedPayloadException, RARP }
import akka.testkit.{ EventFilter, ImplicitSender, TestActors }
import akka.util.ByteString

import scala.concurrent.duration._

object RemoteMessageSerializationSpec {
  class ProxyActor(val one: ActorRef, val another: ActorRef) extends Actor {
    def receive = {
      case s if sender().path == one.path     ⇒ another ! s
      case s if sender().path == another.path ⇒ one ! s
    }
  }
}

class RemoteMessageSerializationSpec extends ArteryMultiNodeSpec("""
    akka.actor.serialize-messages = off
    akka.actor.serialize-creators = off
  """) with ImplicitSender {

  val maxPayloadBytes = RARP(system).provider.remoteSettings.Artery.Advanced.MaximumFrameSize

  val remoteSystem = newRemoteSystem()
  val remotePort = port(remoteSystem)

  "Remote message serialization" should {

    "drop unserializable messages" in {
      object Unserializable
      EventFilter[NotSerializableException](pattern = ".*No configured serialization.*", occurrences = 1).intercept {
        verifySend(Unserializable) {
          expectNoMsg(1.second) // No AssocitionErrorEvent should be published
        }
      }
    }

    "allow messages up to payload size" in {
      val maxProtocolOverhead = 500 // Make sure we're still under size after the message is serialized, etc
      val big = byteStringOfSize(maxPayloadBytes - maxProtocolOverhead)
      verifySend(big) {
        expectMsg(3.seconds, big)
      }
    }

    "drop sent messages over payload size" in {
      val oversized = byteStringOfSize(maxPayloadBytes + 1)
      EventFilter[OversizedPayloadException](start = "Failed to serialize oversized message", occurrences = 1).intercept {
        verifySend(oversized) {
          expectNoMsg(1.second) // No AssocitionErrorEvent should be published
        }
      }
    }

    // TODO max payload size is not configurable yet, so we cannot send a too big message, it fails no sending side
    "drop received messages over payload size" ignore {
      // Receiver should reply with a message of size maxPayload + 1, which will be dropped and an error logged
      EventFilter[OversizedPayloadException](pattern = ".*Discarding oversized payload received.*", occurrences = 1).intercept {
        verifySend(maxPayloadBytes + 1) {
          expectNoMsg(1.second) // No AssocitionErrorEvent should be published
        }
      }
    }

    "be able to serialize a local actor ref from another actor system" in {
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val local = localSystem.actorOf(TestActors.echoActorProps, "echo")

      val remoteEcho = system.actorSelection(rootActorPath(remoteSystem) / "user" / "echo").resolveOne(3.seconds).futureValue
      remoteEcho ! local
      expectMsg(3.seconds, local)
    }

  }

  private def verifySend(msg: Any)(afterSend: ⇒ Unit): Unit = {
    val bigBounceId = s"bigBounce-${ThreadLocalRandom.current.nextInt()}"
    val bigBounceOther = remoteSystem.actorOf(Props(new Actor {
      def receive = {
        case x: Int ⇒ sender() ! byteStringOfSize(x)
        case x      ⇒ sender() ! x
      }
    }), bigBounceId)
    val bigBounceHere = localSystem.actorFor(s"akka://${remoteSystem.name}@localhost:$remotePort/user/$bigBounceId")

    val eventForwarder = localSystem.actorOf(Props(new Actor {
      def receive = {
        case x ⇒ testActor ! x
      }
    }))
    localSystem.eventStream.subscribe(eventForwarder, classOf[AssociationErrorEvent])
    localSystem.eventStream.subscribe(eventForwarder, classOf[DisassociatedEvent])
    try {
      bigBounceHere ! msg
      afterSend
      expectNoMsg(500.millis)
    } finally {
      localSystem.eventStream.unsubscribe(eventForwarder, classOf[AssociationErrorEvent])
      localSystem.eventStream.unsubscribe(eventForwarder, classOf[DisassociatedEvent])
      eventForwarder ! PoisonPill
      bigBounceOther ! PoisonPill
    }
  }

  private def byteStringOfSize(size: Int) = ByteString.fromArray(Array.fill(size)(42: Byte))

}
