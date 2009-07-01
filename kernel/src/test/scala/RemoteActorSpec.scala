package se.scalablesolutions.akka.kernel.actor

import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import kernel.nio.{RemoteServer, RemoteClient}
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

class RemoteActorSpec extends TestCase {

  new Thread(new Runnable() {
     def run = {
       val server = new RemoteServer
       server.connect
     }
  }).start
  Thread.sleep(1000)
  RemoteClient.connect
  
  private val unit = TimeUnit.MILLISECONDS

  @Test
  def testSendOneWay = {
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
  def testSendReplySync = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote
    actor.start
    val result: String = actor !? "Hello"
    assertEquals("World", result)
    actor.stop
  }

  @Test
  def testSendReplyAsync = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote
    actor.start
    val result = actor !! "Hello"
    assertEquals("World", result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def testSendReceiveException = {
    implicit val timeout = 5000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote
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
