/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorRef, ActorSelection, Props, RootActorPath }
import akka.remote.{ LargeDestination, RARP, RegularDestination, RemoteActorRef }
import akka.testkit.SocketUtil._
import akka.testkit.TestProbe
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

object MetadataCarryingSpec {

  val testMetadataHolder = MetadataCarrying.holder

  object TestMetadataListener extends RemoteMetadataListener {
    override def accept(id: Int, metadata: ByteString): Unit = {
      val string = metadata.utf8String
      testMetadataHolder.set(string)
    }
  }

  final case class Ping(payload: ByteString = ByteString.empty)

  class Proxy(to: ActorRef) extends Actor {
    var replyTo: ActorRef = _

    def receive = {
      case Ping(bytes) ⇒
        replyTo = sender()

        println(s"INIT: th: ${Thread.currentThread().getName} => testMetadataHolder = " + testMetadataHolder.get())
        testMetadataHolder.set(s"SET_IN(${self.path.name})")
        println(s"SET : th: ${Thread.currentThread().getName} => testMetadataHolder = " + testMetadataHolder.get())

        sender() ! s"${testMetadataHolder.get()}@${self.path.name}"
        to ! Ping(bytes) // sending should include metadata in envelope

      case s ⇒
        println(s"REC2: th: ${Thread.currentThread().getName} => testMetadataHolder = " + testMetadataHolder.get())
        testMetadataHolder.set(s"SET_IN(${self.path.name})")
        context.actorSelection(replyTo.path) ! s
        context.actorSelection(replyTo.path) ! s"${testMetadataHolder.get()}@${self.path.name}"
        testMetadataHolder.set("")
    }
  }
  class SimpleReply extends Actor {
    def receive = {
      case Ping(bytes) ⇒
        println(s"REC1: th: ${Thread.currentThread().getName} => testMetadataHolder = " + testMetadataHolder.get())
        sender() ! s"${testMetadataHolder.get()}@${self.path.name}"
        testMetadataHolder.set(null)
    }
  }
}

class MetadataCarryingSpec extends ArteryMultiNodeSpec(
  """
    akka {
      loglevel = ERROR
    }
  """.stripMargin) {

  import MetadataCarryingSpec._

  "Metadata should be included in" should {

    "allow for normal communication while simultaneously sending large messages" in {
      val systemA = localSystem
      val remotePort = temporaryServerAddress(udp = true).getPort
      val systemB = newRemoteSystem(extraConfig = Some(s"akka.remote.artery.port=$remotePort"))

      val senderProbeB = TestProbe()(systemB)

      val replyActor = systemA.actorOf(Props(new SimpleReply), "reply")
      val proxyActor = systemB.actorOf(Props(new Proxy(replyActor)), "proxy")
      val proxy = systemA.actorSelection(s"artery://${systemB.name}@localhost:$remotePort/user/proxy")

      proxy.tell(Ping(), senderProbeB.ref)
      senderProbeB.expectMsgType[String] should ===("SET_IN(proxy)@proxy")
      senderProbeB.expectMsgType[String] should ===("SET_IN(proxy)@reply")
      senderProbeB.expectMsgType[String] should ===("SET_IN(proxy)@proxy")
    }
  }

}
