package ticket

import akka.actor.{Actor, ActorRef}
import akka.serialization.RemoteActorSerialization
import akka.actor.Actor.actorOf

import java.util.concurrent.{CountDownLatch, TimeUnit}
import akka.actor.remote.AkkaRemoteTest

case class RecvActorRef(bytes:Array[Byte])

class ActorRefService(latch: CountDownLatch) extends Actor {
  import self._

  def receive:Receive = {
    case RecvActorRef(bytes) =>
      val ref = RemoteActorSerialization.fromBinaryToRemoteActorRef(bytes)
      ref ! "hello"
    case "hello" => latch.countDown
  }
}

class Ticket506Spec extends AkkaRemoteTest {
  "a RemoteActorRef serialized" should {
      "should be remotely usable" in {

      val latch = new CountDownLatch(1)
      val a1 = actorOf( new ActorRefService(null))
      val a2 = actorOf( new ActorRefService(latch))

      remote.register("service1", a1)
      remote.register("service2", a2)

      // connect to the first server/service
      val c1 = remote.actorFor("service1", host, port)

      val bytes = RemoteActorSerialization.toRemoteActorRefProtocol(a2).toByteArray
      c1 ! RecvActorRef(bytes)

      latch.await(1, unit) must be(true)
    }
  }
}
