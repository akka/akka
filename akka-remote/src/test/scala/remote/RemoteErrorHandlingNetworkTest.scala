package akka.actor.remote

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.actor.Actor._
import akka.actor.{ ActorRef, Actor }
import akka.util.duration._
import java.util.concurrent.atomic.AtomicBoolean

object RemoteErrorHandlingNetworkTest {
  case class Send(actor: ActorRef)

  class RemoteActorSpecActorUnidirectional extends Actor {
    self.id = "network-drop:unidirectional"
    def receive = {
      case "Ping" ⇒ self.reply_?("Pong")
    }
  }

  class Decrementer extends Actor {
    def receive = {
      case "done" ⇒ self.reply_?(false)
      case i: Int if i > 0 ⇒
        self.reply_?(i - 1)
      case i: Int ⇒
        self.reply_?(0)
        this become {
          case "done" ⇒ self.reply_?(true)
          case _      ⇒ //Do Nothing
        }
    }
  }

  class RemoteActorSpecActorBidirectional extends Actor {

    def receive = {
      case "Hello" ⇒
        self.reply("World")
      case "Failure" ⇒
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  class RemoteActorSpecActorAsyncSender(latch: CountDownLatch) extends Actor {
    def receive = {
      case Send(actor: ActorRef) ⇒
        actor ! "Hello"
      case "World" ⇒ latch.countDown()
    }
  }
}

class RemoteErrorHandlingNetworkTest extends AkkaRemoteTest with NetworkFailureTest {
  import RemoteErrorHandlingNetworkTest._

  "Remote actors" should {

    "be able to recover from network drop without loosing any messages" in {
      validateSudo()
      val latch = new CountDownLatch(10)
      implicit val sender = replyHandler(latch, "Pong")
      val service = actorOf[RemoteActorSpecActorUnidirectional]
      remote.register(service.id, service)
      val actor = remote.actorFor(service.id, 5000L, host, port)
      actor ! "Ping"
      actor ! "Ping"
      actor ! "Ping"
      actor ! "Ping"
      actor ! "Ping"
      val dead = new AtomicBoolean(false)
      dropNetworkFor(10 seconds, dead) // drops the network - in another thread - so async
      sleepFor(2 seconds) // wait until network drop is done before sending the other messages
      try { actor ! "Ping" } catch { case e ⇒ () } // queue up messages
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      latch.await(15, TimeUnit.SECONDS) must be(true) // network should be restored and the messages delivered
      dead.get must be(false)
    }

    "be able to recover from TCP RESET without loosing any messages" in {
      validateSudo()
      val latch = new CountDownLatch(10)
      implicit val sender = replyHandler(latch, "Pong")
      val service = actorOf[RemoteActorSpecActorUnidirectional]
      remote.register(service.id, service)
      val actor = remote.actorFor(service.id, 5000L, host, port)
      actor ! "Ping"
      actor ! "Ping"
      actor ! "Ping"
      actor ! "Ping"
      actor ! "Ping"
      val dead = new AtomicBoolean(false)
      replyWithTcpResetFor(10 seconds, dead)
      sleepFor(2 seconds)
      try { actor ! "Ping" } catch { case e ⇒ () } // queue up messages
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      try { actor ! "Ping" } catch { case e ⇒ () } // ...
      latch.await(15, TimeUnit.SECONDS) must be(true)
      dead.get must be(false)
    }
    /*
    "sendWithBangAndGetReplyThroughSenderRef" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      implicit val timeout = 500000000L
      val actor = remote.actorFor(
        "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", timeout, host, port)
      val latch = new CountDownLatch(1)
      val sender = actorOf( new RemoteActorSpecActorAsyncSender(latch) ).start()
      sender ! Send(actor)
      latch.await(1, TimeUnit.SECONDS) must be (true)
    }
    */
  }
}

