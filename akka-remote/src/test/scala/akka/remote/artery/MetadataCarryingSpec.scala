/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ Actor, ActorRef, Props }
import akka.remote.artery.MetadataCarryingSpy.{ RemoteMessageReceived, RemoteMessageSent }
import akka.testkit.SocketUtil._
import akka.testkit.TestProbe
import akka.util.{ ByteString, OptionVal }

object MetadataCarryingSpy {
  def ref: Option[ActorRef] = Option(_ref.get())
  def setProbe(bs: ActorRef): Unit = _ref.set(bs)
  private[this] val _ref = new AtomicReference[ActorRef]()

  case class RemoteMessageSent(recipient: OptionVal[ActorRef], message: Object, sender: OptionVal[ActorRef], context: OptionVal[AnyRef])
  case class RemoteMessageReceived(recipient: OptionVal[ActorRef], message: Object, sender: OptionVal[ActorRef], metadata: ByteString)
}

class TestInstrument extends RemoteInstrument {

  override val identifier: Byte = 1

  // TODO remove from the Akka one or not? It explains why we pass in metadata in the remoteMessageSent method
  override def remoteActorTold(actorRef: ActorRef, message: Any, sender: ActorRef): OptionVal[AnyRef] = {
    OptionVal.None // THIS IS NOT USED
  }

  override def remoteMessageSent(recipient: OptionVal[ActorRef], message: Object, sender: OptionVal[ActorRef], context: OptionVal[AnyRef]): OptionVal[ByteString] = {
    recipient match {
      case OptionVal.Some(rec) ⇒
        val metadata = ByteString("!!!")
        MetadataCarryingSpy.ref.foreach(_ ! RemoteMessageSent(recipient, message, sender, context))
        OptionVal.Some(metadata) // this data will be attached to the remote message
      case _ ⇒
        OptionVal.None
    }

  }

  override def remoteMessageReceived(recipient: OptionVal[ActorRef], message: Object, sender: OptionVal[ActorRef], metadata: ByteString): Unit = {
    MetadataCarryingSpy.ref.foreach(_ ! RemoteMessageReceived(recipient, message, sender, metadata))
  }
}

object MetadataCarryingSpec {

  final case class Ping(payload: ByteString = ByteString.empty)

  class Proxy(to: ActorRef) extends Actor {
    var replyTo: ActorRef = _

    def receive = {
      case Ping(bytes) ⇒
        replyTo = sender()
        to ! Ping(bytes) // sending should include metadata in envelope

      case s ⇒
        context.actorSelection(replyTo.path) ! s
    }
  }
  class SimpleReply extends Actor {
    def receive = {
      case Ping(bytes) ⇒ // sender() ! s"@${self.path.name}"
    }
  }
}

class MetadataCarryingSpec extends ArteryMultiNodeSpec(
  """
    akka {
      loglevel = ERROR
      
      remote.artery.advanced {
        instruments = [ "akka.remote.artery.TestInstrument" ]
      }
    }
  """.stripMargin) {

  import MetadataCarryingSpec._

  "Metadata should be included in" should {

    "allow for normal communication while simultaneously sending large messages" in {
      val systemA = localSystem
      val remotePort = temporaryServerAddress(udp = true).getPort
      val systemB = newRemoteSystem(extraConfig = Some(s"akka.remote.artery.port=$remotePort"))

      val instrumentProbe = TestProbe()(systemB)
      MetadataCarryingSpy.setProbe(instrumentProbe.ref)

      val replyActor = systemA.actorOf(Props(new SimpleReply), "reply")
      val proxyActor = systemB.actorOf(Props(new Proxy(replyActor)), "proxy")
      val proxy = systemA.actorSelection(s"artery://${systemB.name}@localhost:$remotePort/user/proxy")

      proxy.tell(Ping(), instrumentProbe.ref)

      // TODO add more assertions (but tricky, since actor selection)
      val sent = instrumentProbe.expectMsgType[RemoteMessageSent]
      sent.context should ===(OptionVal.None)
      val recvd = instrumentProbe.expectMsgType[RemoteMessageReceived]
      recvd.metadata should ===(ByteString("!!!"))
    }
  }

}
