package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import junit.framework.TestCase

import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

case class Send(actor: Actor)

object RemoteActorSpecActorUnidirectional {
  val latch = new CountDownLatch(1)
}
class RemoteActorSpecActorUnidirectional extends Actor {
  self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)

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

class SendOneWayAndReplyReceiverActor extends Actor {
  def receive = {
    case "Hello" =>
      self.reply("World")
  }
}

object SendOneWayAndReplySenderActor {
  val latch = new CountDownLatch(1)
}
class SendOneWayAndReplySenderActor extends Actor {
  var state: Option[AnyRef] = None
  var sendTo: ActorRef = _
  var latch: CountDownLatch = _

  def sendOff = sendTo ! "Hello"

  def receive = {
    case msg: AnyRef =>
      state = Some(msg)
      SendOneWayAndReplySenderActor.latch.countDown
  }
}

class ClientInitiatedRemoteActorSpec extends JUnitSuite {
  se.scalablesolutions.akka.config.Config.config

  val HOSTNAME = "localhost"
  val PORT1 = 9990
  val PORT2 = 9991
  var s1: RemoteServer = null

  private val unit = TimeUnit.MILLISECONDS

  @Before
  def init() {
    s1 = new RemoteServer()
    s1.start(HOSTNAME, PORT1)
    Thread.sleep(1000)
  }

  @After
  def finished() {
    s1.shutdown
    RemoteClient.shutdownAll
    Thread.sleep(1000)
  }

  @Test
  def shouldSendOneWay = {
    val actor = actorOf[RemoteActorSpecActorUnidirectional]
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    actor ! "OneWay"
    assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test
  def shouldSendOneWayAndReceiveReply = {
    val actor = actorOf[SendOneWayAndReplyReceiverActor]
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    val sender = actorOf[SendOneWayAndReplySenderActor]
    sender.homeAddress = (HOSTNAME, PORT2)
    sender.actor.asInstanceOf[SendOneWayAndReplySenderActor].sendTo = actor
    sender.start
    sender.actor.asInstanceOf[SendOneWayAndReplySenderActor].sendOff
    assert(SendOneWayAndReplySenderActor.latch.await(1, TimeUnit.SECONDS))
    assert(sender.actor.asInstanceOf[SendOneWayAndReplySenderActor].state.isDefined === true)
    assert("World" === sender.actor.asInstanceOf[SendOneWayAndReplySenderActor].state.get.asInstanceOf[String])
    actor.stop
    sender.stop
  }

  @Test
  def shouldSendBangBangMessageAndReceiveReply = {
    val actor = actorOf[RemoteActorSpecActorBidirectional]
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldSendAndReceiveRemoteException {
    implicit val timeout = 500000000L
    val actor = actorOf[RemoteActorSpecActorBidirectional]
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("Expected exception; to test fault-tolerance" === e.getMessage())
    }
    actor.stop
  }
}

