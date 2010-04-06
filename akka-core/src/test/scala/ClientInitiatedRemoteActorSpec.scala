package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import junit.framework.TestCase

import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.dispatch.Dispatchers

object Global {
  val oneWay = new CountDownLatch(1)
  val remoteReply = new CountDownLatch(1)
}
class RemoteActorSpecActorUnidirectional extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  def receive = {
    case "OneWay" =>
      Global.oneWay.countDown
  }
}

class RemoteActorSpecActorBidirectional extends Actor {
  def receive = {
    case "Hello" =>
      reply("World")
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

case class Send(actor: Actor)

class RemoteActorSpecActorAsyncSender extends Actor {
  def receive = {
    case Send(actor: Actor) =>
      actor ! "Hello"
    case "World" =>
      Global.remoteReply.countDown
  }

  def send(actor: Actor) {
    this ! Send(actor)
  }
}

class SendOneWayAndReplyReceiverActor extends Actor {
  def receive = {
    case "Hello" =>
      reply("World")
  }
}

class SendOneWayAndReplySenderActor extends Actor {
  var state: Option[AnyRef] = None
  var sendTo: Actor = _
  var latch: CountDownLatch = _

  def sendOff = sendTo ! "Hello"

  def receive = {
    case msg: AnyRef =>
      state = Some(msg)
      latch.countDown
  }
}

class ClientInitiatedRemoteActorSpec extends JUnitSuite {
  se.scalablesolutions.akka.config.Config.config

  val HOSTNAME = "localhost"
  val PORT1 = 9990
  val PORT2 = 9991
  var s1: RemoteServer = null
  var s2: RemoteServer = null

  @Before
  def init() {
    s1 = new RemoteServer()
    s2 = new RemoteServer()

    s1.start(HOSTNAME, PORT1)
    s2.start(HOSTNAME, PORT2)
    Thread.sleep(1000)
  }

  private val unit = TimeUnit.MILLISECONDS

  // make sure the servers shutdown cleanly after the test has
  // finished
  @After
  def finished() {
    s1.shutdown
    s2.shutdown
    RemoteClient.shutdownAll
    Thread.sleep(1000)
  }

  @Test
  def shouldSendOneWay = {
    val actor = new RemoteActorSpecActorUnidirectional
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    actor ! "OneWay"
    assert(Global.oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test
  def shouldSendOneWayAndReceiveReply = {
    val actor = new SendOneWayAndReplyReceiverActor
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    val latch = new CountDownLatch(1)
    val sender = new SendOneWayAndReplySenderActor
    sender.setReplyToAddress(HOSTNAME, PORT2)
    sender.sendTo = actor
    sender.latch = latch
    sender.start
    sender.sendOff
    assert(latch.await(1, TimeUnit.SECONDS))
    assert(sender.state.isDefined === true)
    assert("World" === sender.state.get.asInstanceOf[String])
    actor.stop
    sender.stop
  }

  @Test
  def shouldSendReplyAsync = {
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldSendRemoteReply = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.setReplyToAddress(HOSTNAME, PORT2)
    actor.makeRemote(HOSTNAME, PORT2)
    actor.start

    val sender = new RemoteActorSpecActorAsyncSender
    sender.setReplyToAddress(HOSTNAME, PORT1)
    sender.start
    sender.send(actor)
    assert(Global.remoteReply.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test
  def shouldSendReceiveException = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(HOSTNAME, PORT1)
    actor.start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("expected" === e.getMessage())
    }
    actor.stop
  }
}

