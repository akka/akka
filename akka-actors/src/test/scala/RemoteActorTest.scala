package se.scalablesolutions.akka.actor

import java.util.concurrent.TimeUnit
import junit.framework.TestCase

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.nio.{RemoteNode, RemoteServer}
import se.scalablesolutions.akka.dispatch.Dispatchers

object Global {
  var oneWay = "nada"  
}
class RemoteActorSpecActorUnidirectional extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  def receive = {
    case "OneWay" =>
      Global.oneWay = "received"
  }
}

class RemoteActorSpecActorBidirectional extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  def receive = {
    case "Hello" =>
      reply("World")
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

class RemoteActorTest extends JUnitSuite   {
  import Actor.Sender.Self

  akka.Config.config
  new Thread(new Runnable() {
     def run = {
       RemoteNode.start
     }
  }).start
  Thread.sleep(1000)
  
  private val unit = TimeUnit.MILLISECONDS

  @Test
  def shouldSendOneWay = {
    val actor = new RemoteActorSpecActorUnidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    actor.start
    val result = actor ! "OneWay"
    Thread.sleep(100)
    assert("received" === Global.oneWay)
    actor.stop
  }

  @Test
  def shouldSendReplyAsync = {
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    actor.start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldSendReceiveException = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
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
