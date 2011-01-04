package akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.Actor._
import akka.actor.{ActorRegistry, ActorRef, Actor}

object ServerInitiatedRemoteActorSpec {
  case class Send(actor: ActorRef)

  class RemoteActorSpecActorUnidirectional extends Actor {
    def receive = {
      case "Ping" => self.reply_?("Pong")
    }
  }

  class Decrementer extends Actor {
    def receive = {
      case "done" => self.reply_?(false)
      case i: Int if i > 0 =>
        self.reply_?(i - 1)
      case i: Int =>
        self.reply_?(0)
        this become {
          case "done" => self.reply_?(true)
          case _ => //Do Nothing
        }
    }
  }

  class RemoteActorSpecActorBidirectional extends Actor {

    def receive = {
      case "Hello" =>
        self.reply("World")
      case "Failure" =>
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  class RemoteActorSpecActorAsyncSender(latch: CountDownLatch) extends Actor {
    def receive = {
      case Send(actor: ActorRef) =>
        actor ! "Hello"
      case "World" => latch.countDown
    }
  }
}

class ServerInitiatedRemoteActorSpec extends AkkaRemoteTest {
  import ServerInitiatedRemoteActorSpec._

  "Server-managed remote actors" should {
    "sendWithBang" in {
      val latch = new CountDownLatch(1)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register(actorOf[RemoteActorSpecActorUnidirectional])
      val actor = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional",5000L,host, port)

      actor ! "Ping"
      latch.await(1, TimeUnit.SECONDS) must be (true)
    }

    "sendWithBangBangAndGetReply" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      val actor = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", 5000L,host, port)
      (actor !! "Hello").as[String].get must equal ("World")
    }

    "sendWithBangAndGetReplyThroughSenderRef" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      implicit val timeout = 500000000L
      val actor = remote.actorFor(
        "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", timeout,host, port)
      val latch = new CountDownLatch(1)
      val sender = actorOf( new RemoteActorSpecActorAsyncSender(latch) ).start
      sender ! Send(actor)
      latch.await(1, TimeUnit.SECONDS) must be (true)
    }

    "sendWithBangBangAndReplyWithException" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      implicit val timeout = 500000000L
      val actor = remote.actorFor(
          "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", timeout, host, port)
      try {
        actor !! "Failure"
        fail("Should have thrown an exception")
      } catch {
        case e => e.getMessage must equal ("Expected exception; to test fault-tolerance")
      }
    }

    "notRecreateRegisteredActor" in {
      val latch = new CountDownLatch(1)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register(actorOf[RemoteActorSpecActorUnidirectional])
      val actor = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", host, port)
      val numberOfActorsInRegistry = Actor.registry.actors.length
      actor ! "Ping"
      latch.await(1, TimeUnit.SECONDS) must be (true)
      numberOfActorsInRegistry must equal (Actor.registry.actors.length)
    }

    "UseServiceNameAsIdForRemoteActorRef" in {
      val latch = new CountDownLatch(3)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register(actorOf[RemoteActorSpecActorUnidirectional])
      remote.register("my-service", actorOf[RemoteActorSpecActorUnidirectional])
      val actor1 = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", host, port)
      val actor2 = remote.actorFor("my-service", host, port)
      val actor3 = remote.actorFor("my-service", host, port)

      actor1 ! "Ping"
      actor2 ! "Ping"
      actor3 ! "Ping"

      latch.await(1, TimeUnit.SECONDS) must be (true)
      actor1.uuid must not equal actor2.uuid
      actor1.uuid must not equal actor3.uuid
      actor1.id must not equal actor2.id
      actor2.id must equal (actor3.id)
    }

    "shouldFindActorByUuid" in {
      val latch = new CountDownLatch(2)
      implicit val sender = replyHandler(latch, "Pong")
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
      val actor2 = actorOf[RemoteActorSpecActorUnidirectional]
      remote.register("uuid:" + actor1.uuid, actor1)
      remote.register("my-service", actor2)

      val ref1 = remote.actorFor("uuid:" + actor1.uuid, host, port)
      val ref2 = remote.actorFor("my-service", host, port)

      ref1 ! "Ping"
      ref2 ! "Ping"
      latch.await(1, TimeUnit.SECONDS) must be (true)
    }

    "shouldRegisterAndUnregister" in {
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]

      remote.register("my-service-1", actor1)
      remote.actors.get("my-service-1") must not be null

      remote.unregister("my-service-1")
      remote.actors.get("my-service-1") must be (null)
    }

    "shouldRegisterAndUnregisterByUuid" in {
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
      val uuid = "uuid:" + actor1.uuid

      remote.register(uuid, actor1)
      remote.actorsByUuid.get(actor1.uuid.toString) must not be null

      remote.unregister(uuid)
      remote.actorsByUuid.get(actor1.uuid) must be (null)
    }

    "shouldHandleOneWayReplyThroughPassiveRemoteClient" in {
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
      remote.register("foo", actor1)
      val latch = new CountDownLatch(1)
      val actor2 = actorOf(new Actor { def receive = { case "Pong" => latch.countDown } }).start

      val remoteActor = remote.actorFor("foo", host, port)
      remoteActor.!("Ping")(Some(actor2))
      latch.await(3,TimeUnit.SECONDS) must be (true)
    }

    "should be able to remotely communicate between 2 server-managed actors" in {
      val localFoo = actorOf[Decrementer]
      val localBar = actorOf[Decrementer]
      remote.register("foo", localFoo)
      remote.register("bar", localBar)

      val remoteFoo = remote.actorFor("foo", host, port)
      val remoteBar = remote.actorFor("bar", host, port)

      //Seed the start
      remoteFoo.!(10)(Some(remoteBar))

      val latch = new CountDownLatch(100)

      def testDone() = (remoteFoo !! "done").as[Boolean].getOrElse(false) &&
                       (remoteBar !! "done").as[Boolean].getOrElse(false)

      while(!testDone()) {
        if (latch.await(200, TimeUnit.MILLISECONDS))
          error("Test didn't complete within 100 cycles")
        else
          latch.countDown
      }

      val decrementers = Actor.registry.actorsFor[Decrementer]
      decrementers must have size(2) //No new are allowed to have been created
      decrementers.find( _ eq localFoo) must equal (Some(localFoo))
      decrementers.find( _ eq localBar) must equal (Some(localBar))
    }
  }
}

