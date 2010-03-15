package se.scalablesolutions.akka.actor

import java.util.concurrent.TimeUnit
import junit.framework.TestCase

import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.dispatch.Dispatchers

object Global {
  var oneWay = "nada"
  var remoteReply = "nada"
}
class RemoteActorSpecActorUnidirectional extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  def receive = {
    case "OneWay" =>
      Global.oneWay = "received"
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
      Global.remoteReply = "replied"
  }

  def send(actor: Actor) {
    this ! Send(actor)
  }
}

class ClientInitiatedRemoteActorTest extends JUnitSuite {
  import Actor.Sender.Self

  akka.config.Config.config

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
    val result = actor ! "OneWay"
    Thread.sleep(1000)
    assert("received" === Global.oneWay)
    actor.stop
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
    actor.makeRemote(HOSTNAME, PORT2)
    actor.start

    val sender = new RemoteActorSpecActorAsyncSender
    sender.setReplyToAddress(HOSTNAME, PORT1)
    sender.start
    sender.send(actor)
    Thread.sleep(1000)
    assert("replied" === Global.remoteReply)
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
