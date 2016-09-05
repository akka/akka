/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.actor.ActorSelectionMessage
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.remote.artery.MetadataCarryingSpy.{ RemoteMessageReceived, RemoteMessageSent }
import akka.testkit.ImplicitSender
import akka.testkit.SocketUtil._
import akka.testkit.TestActors
import akka.testkit.TestProbe
import akka.util.ByteString

object MetadataCarryingSpy extends ExtensionId[MetadataCarryingSpy] with ExtensionIdProvider {
  override def get(system: ActorSystem): MetadataCarryingSpy = super.get(system)
  override def lookup = MetadataCarryingSpy
  override def createExtension(system: ExtendedActorSystem): MetadataCarryingSpy = new MetadataCarryingSpy

  final case class RemoteMessageSent(recipient: ActorRef, message: Object, sender: ActorRef)
  final case class RemoteMessageReceived(recipient: ActorRef, message: Object, sender: ActorRef, metadata: ByteString)
}

class MetadataCarryingSpy extends Extension {
  def ref: Option[ActorRef] = Option(_ref.get())
  def setProbe(bs: ActorRef): Unit = _ref.set(bs)
  private[this] val _ref = new AtomicReference[ActorRef]()
}

class TestInstrument(system: ExtendedActorSystem) extends RemoteInstrument {

  override val identifier: Byte = 1

  override def remoteMessageSent(recipient: ActorRef, message: Object, sender: ActorRef): ByteString =
    message match {
      case _: MetadataCarryingSpec.Ping | ActorSelectionMessage(_: MetadataCarryingSpec.Ping, _, _) ⇒
        val metadata = ByteString("!!!")
        MetadataCarryingSpy(system).ref.foreach(_ ! RemoteMessageSent(recipient, message, sender))
        metadata // this data will be attached to the remote message
      case _ ⇒
        null
    }

  override def remoteMessageReceived(recipient: ActorRef, message: Object, sender: ActorRef, metadata: ByteString): Unit =
    message match {
      case _: MetadataCarryingSpec.Ping | ActorSelectionMessage(_: MetadataCarryingSpec.Ping, _, _) ⇒
        MetadataCarryingSpy(system).ref.foreach(_ ! RemoteMessageReceived(recipient, message, sender, metadata))
      case _ ⇒
    }
}

object MetadataCarryingSpec {
  final case class Ping(payload: ByteString = ByteString.empty)
}

class MetadataCarryingSpec extends ArteryMultiNodeSpec(
  """
    akka {
      remote.artery.advanced {
        instruments = [ "akka.remote.artery.TestInstrument" ]
      }
    }
  """) with ImplicitSender {

  import MetadataCarryingSpec._

  "Metadata" should {

    "be included in remote messages" in {
      val systemA = localSystem
      val systemB = newRemoteSystem(name = Some("systemB"))

      val instrumentProbeA = TestProbe()(systemA)
      MetadataCarryingSpy(systemA).setProbe(instrumentProbeA.ref)
      val instrumentProbeB = TestProbe()(systemB)
      MetadataCarryingSpy(systemB).setProbe(instrumentProbeB.ref)

      systemB.actorOf(TestActors.echoActorProps, "reply")
      systemA.actorSelection(rootActorPath(systemB) / "user" / "reply") ! Ping()
      expectMsgType[Ping]

      val sentA = instrumentProbeA.expectMsgType[RemoteMessageSent]
      val recvdB = instrumentProbeB.expectMsgType[RemoteMessageReceived]
      recvdB.metadata should ===(ByteString("!!!"))

      // for the reply
      val sentB = instrumentProbeB.expectMsgType[RemoteMessageSent]
      val recvdA = instrumentProbeA.expectMsgType[RemoteMessageReceived]
      recvdA.metadata should ===(ByteString("!!!"))
    }
  }

}
