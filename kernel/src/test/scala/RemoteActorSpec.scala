package se.scalablesolutions.akka.kernel.actor

import concurrent.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kernel.nio.{NettyClient, NettyServer}
import reactor._

import org.junit.{Test, Before}
import org.junit.Assert._

object Global {
  var oneWay = "nada"  
}
class RemoteActorSpecActorUnidirectional extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case "OneWay" =>
      Global.oneWay = "received"
  }
}

class RemoteActorSpecActorBidirectional extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case "Hello" =>
      reply("World")
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

class RemoteActorSpec {

  new Thread(new Runnable() {
     def run = {
       val server = new NettyServer
       server.connect
     }
  }).start
  NettyClient.connect
  
  private val unit = TimeUnit.MILLISECONDS

  @Test
  def sendOneWay = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorUnidirectional
    actor.makeRemote
    actor.start
    val result = actor ! "OneWay"
    Thread.sleep(100)
    assertEquals("received", Global.oneWay)
    actor.stop
  }

  @Test
  def sendReplySync = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote
    actor.start
    val result: String = actor !? "Hello"
    assertEquals("World", result)
    actor.stop
  }

  @Test
  def sendReplyAsync = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote
    actor.start
    val result = actor !! "Hello"
    assertEquals("World", result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def sendReceiveException = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.isRemote = true
    actor.start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assertEquals("expected", e.getMessage())
    }
    actor.stop
  }
}
