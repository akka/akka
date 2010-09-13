package se.scalablesolutions.akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActorRegistry, ActorRef, Actor}

object ServerInitiatedRemoteActorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null

  case class Send(actor: ActorRef)

  object RemoteActorSpecActorUnidirectional {
    val latch = new CountDownLatch(1)
  }
  class RemoteActorSpecActorUnidirectional extends Actor {

    def receive = {
      case "OneWay" =>
        RemoteActorSpecActorUnidirectional.latch.countDown
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

  object RemoteActorSpecActorAsyncSender {
    val latch = new CountDownLatch(1)
  }
  class RemoteActorSpecActorAsyncSender extends Actor {

    def receive = {
      case Send(actor: ActorRef) =>
        actor ! "Hello"
      case "World" =>
        RemoteActorSpecActorAsyncSender.latch.countDown
    }
  }
}

class ServerInitiatedRemoteActorSpec extends JUnitSuite {
  import ServerInitiatedRemoteActorSpec._
  private val unit = TimeUnit.MILLISECONDS

  @Before
  def init {
    server = new RemoteServer()

    server.start(HOSTNAME, PORT)

    server.register(actorOf[RemoteActorSpecActorUnidirectional])
    server.register(actorOf[RemoteActorSpecActorBidirectional])
    server.register(actorOf[RemoteActorSpecActorAsyncSender])

    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  @After
  def finished {
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  
  @Test
  def shouldSendWithBang  {
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional",
      5000L,
      HOSTNAME, PORT)
    val result = actor ! "OneWay"
    assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test
  def shouldSendWithBangBangAndGetReply {
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional",
      5000L,
      HOSTNAME, PORT)
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldSendWithBangAndGetReplyThroughSenderRef  {
    implicit val timeout = 500000000L
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional",
      timeout,
      HOSTNAME, PORT)
    val sender = actorOf[RemoteActorSpecActorAsyncSender]
    sender.homeAddress = (HOSTNAME, PORT + 1)
    sender.start
    sender ! Send(actor)
    assert(RemoteActorSpecActorAsyncSender.latch.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test
  def shouldSendWithBangBangAndReplyWithException  {
    implicit val timeout = 500000000L
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional",
      timeout,
      HOSTNAME, PORT)
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("Expected exception; to test fault-tolerance" === e.getMessage())
    }
    actor.stop
  }

  @Test
  def reflectiveAccessShouldNotCreateNewRemoteServerObject {
      val server1 = new RemoteServer()
      server1.start("localhost", 9990)

      var found = RemoteServer.serverFor("localhost", 9990)
      assert(found.isDefined, "sever not found")

      val a = actor { case _ => }

      found = RemoteServer.serverFor("localhost", 9990)
      assert(found.isDefined, "sever not found after creating an actor")
    }


  @Test
  def shouldNotRecreateRegisteredActor {
    server.register(actorOf[RemoteActorSpecActorUnidirectional])
    val actor = RemoteClient.actorFor("se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", HOSTNAME, PORT)
    val numberOfActorsInRegistry = ActorRegistry.actors.length
    actor ! "OneWay"
    assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    assert(numberOfActorsInRegistry === ActorRegistry.actors.length)
    actor.stop
  }

  @Test
  def shouldUseServiceNameAsIdForRemoteActorRef {
    server.register(actorOf[RemoteActorSpecActorUnidirectional])
    server.register("my-service", actorOf[RemoteActorSpecActorUnidirectional])
    val actor1 = RemoteClient.actorFor("se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", HOSTNAME, PORT)
    val actor2 = RemoteClient.actorFor("my-service", HOSTNAME, PORT)
    val actor3 = RemoteClient.actorFor("my-service", HOSTNAME, PORT)

    actor1 ! "OneWay"
    actor2 ! "OneWay"
    actor3 ! "OneWay"

    assert(actor1.uuid != actor2.uuid)
    assert(actor1.uuid != actor3.uuid)
    assert(actor1.id != actor2.id)
    assert(actor2.id == actor3.id)
  }

}

