package akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import akka.remote.{RemoteServer, RemoteClient}
import akka.actor.Actor._
import akka.actor.{ActorRegistry, ActorRef, Actor}
import scala.collection.mutable.Set

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

  case class Login(user:String);
  case class GetUser();
  case class DoSomethingFunny();

  val instantiatedSessionActors= Set[ActorRef]();

  class RemoteStatefullSessionActorSpec extends Actor {

    var user : String= "anonymous";

    override def preStart = {
      instantiatedSessionActors += self;
    }

    override def postStop = {
      instantiatedSessionActors -= self;
    }
    
    def receive = {
      case Login(user) => 
	      this.user = user;
      case GetUser() =>
        self.reply(this.user)
      case DoSomethingFunny() =>
        throw new Exception("Bad boy")
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
    server.registerPerSession("untyped-session-actor-service", actorOf[RemoteStatefullSessionActorSpec])

    Thread.sleep(1000)
  }

  // make sure the servers postStop cleanly after the test has finished
  @After
  def finished {
    try {
      server.shutdown
      val s2 = RemoteServer.serverFor(HOSTNAME, PORT + 1)
      if (s2.isDefined) s2.get.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  @Test
  def shouldSendWithBang  {
    val actor = RemoteClient.actorFor(
      "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional",
      5000L,
      HOSTNAME, PORT)
    val result = actor ! "OneWay"
    assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test
  def shouldSendWithBangBangAndGetReply {
    val actor = RemoteClient.actorFor(
      "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional",
      5000L,
      HOSTNAME, PORT)
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldKeepSessionInformation {

    //RemoteClient.clientFor(HOSTNAME, PORT).connect

    val session1 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)


    val default1 = session1 !! GetUser();
    assert("anonymous" === default1.get.asInstanceOf[String])

    session1 ! Login("session[1]");

    val result1 = session1 !! GetUser();
    assert("session[1]" === result1.get.asInstanceOf[String])

    session1.stop()

    RemoteClient.shutdownAll

    //RemoteClient.clientFor(HOSTNAME, PORT).connect

    val session2 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)

    // since this is a new session, the server should reset the state
    val default2 = session2 !! GetUser();
    assert("anonymous" === default2.get.asInstanceOf[String])

    session2.stop()

    RemoteClient.shutdownAll
  }

  @Test
  def shouldStopActorOnDisconnect{


    val session1 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)


    val default1 = session1 !! GetUser();
    assert("anonymous" === default1.get.asInstanceOf[String])

    assert(instantiatedSessionActors.size == 1);

    RemoteClient.shutdownAll
    Thread.sleep(1000)
    assert(instantiatedSessionActors.size == 0);

  }

  @Test
  def shouldStopActorOnError{


    val session1 = RemoteClient.actorFor(
      "untyped-session-actor-service",
      5000L,
      HOSTNAME, PORT)


    session1 ! DoSomethingFunny();
    session1.stop()

    RemoteClient.shutdownAll
    Thread.sleep(1000)

    assert(instantiatedSessionActors.size == 0);

  }

  @Test
  def shouldSendWithBangAndGetReplyThroughSenderRef  {
    implicit val timeout = 500000000L
    val actor = RemoteClient.actorFor(
      "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional",
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
      "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional",
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

      val a = actorOf( new Actor { def receive = { case _ => } } ).start

      found = RemoteServer.serverFor("localhost", 9990)
      assert(found.isDefined, "sever not found after creating an actor")
    }


  @Test
  def shouldNotRecreateRegisteredActor {
    server.register(actorOf[RemoteActorSpecActorUnidirectional])
    val actor = RemoteClient.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", HOSTNAME, PORT)
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
    val actor1 = RemoteClient.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", HOSTNAME, PORT)
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

  @Test
  def shouldFindActorByUuid {
    val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
    val actor2 = actorOf[RemoteActorSpecActorUnidirectional]
    server.register("uuid:" + actor1.uuid, actor1)
    server.register("my-service", actor2)

    val ref1 = RemoteClient.actorFor("uuid:" + actor1.uuid, HOSTNAME, PORT)
    val ref2 = RemoteClient.actorFor("my-service", HOSTNAME, PORT)

    ref1 ! "OneWay"
    assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    ref1.stop
    ref2 ! "OneWay"
    ref2.stop

  }

  @Test
  def shouldRegisterAndUnregister {
    val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
    server.register("my-service-1", actor1)
    assert(server.actors.get("my-service-1") ne null, "actor registered")
    server.unregister("my-service-1")
    assert(server.actors.get("my-service-1") eq null, "actor unregistered")
  }
  @Test
  def shouldRegisterAndUnregisterSession {
    server.registerPerSession("my-service-1", actorOf[RemoteActorSpecActorUnidirectional])
    assert(server.actorsFactories.get("my-service-1") ne null, "actor registered")
    server.unregisterPerSession("my-service-1")
    assert(server.actorsFactories.get("my-service-1") eq null, "actor unregistered")
  }

  @Test
  def shouldRegisterAndUnregisterByUuid {
    val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
    server.register("uuid:" + actor1.uuid, actor1)
    assert(server.actorsByUuid.get(actor1.uuid.toString) ne null, "actor registered")
    server.unregister("uuid:" + actor1.uuid)
    assert(server.actorsByUuid.get(actor1.uuid) eq null, "actor unregistered")
  }

}

