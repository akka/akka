package ticket

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers

import akka.remote.{RemoteClient, RemoteNode, RemoteServer}
import akka.actor.{Actor, ActorRef}
import akka.serialization.RemoteActorSerialization
import akka.actor.Actor.actorOf

import java.util.concurrent.{CountDownLatch, TimeUnit}

object State {
  val latch = new CountDownLatch(1)
}

case class RecvActorRef(bytes:Array[Byte])

class ActorRefService extends Actor {
  import self._

  def receive:Receive = {
    case RecvActorRef(bytes) =>
      val ref = RemoteActorSerialization.fromBinaryToRemoteActorRef(bytes)
      ref ! "hello"
    case "hello" =>
      State.latch.countDown
  }
}

class Ticket506Spec extends Spec with ShouldMatchers  {
  val hostname:String = "localhost"
  val port:Int = 9440

  describe("a RemoteActorRef serialized") {
      it("should be remotely usable") {
      val s1,s2 = new RemoteServer
      s1.start(hostname, port)
      s2.start(hostname, port + 1)

      val a1,a2 = actorOf[ActorRefService]
      a1.homeAddress = (hostname, port)
      a2.homeAddress = (hostname, port+1)

      s1.register("service", a1)
      s2.register("service", a2)

      // connect to the first server/service
      val c1 = RemoteClient.actorFor("service", hostname, port)

      val bytes = RemoteActorSerialization.toRemoteActorRefProtocol(a2).toByteArray
      c1 ! RecvActorRef(bytes)

      State.latch.await(1000, TimeUnit.MILLISECONDS) should be(true)

      RemoteClient.shutdownAll
      s1.shutdown
      s2.shutdown
    }
  }
}
