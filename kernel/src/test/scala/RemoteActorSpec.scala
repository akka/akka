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

  kernel.Kernel.config
  new Thread(new Runnable() {
     def run = {
       val server = new RemoteServer
       server.start
     }
  }).start
  Thread.sleep(1000)
  
  private val unit = TimeUnit.MILLISECONDS

  @Test
  def testSendOneWay = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorUnidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    actor.start
    val result = actor ! "OneWay"
    Thread.sleep(100)
    assertEquals("received", Global.oneWay)
    actor.stop
  }

  @Test
  def testSendReplySync = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    actor.start
    val result: String = actor !? "Hello"
    assertEquals("World", result)
    actor.stop
  }

  @Test
  def testSendReplyAsync = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
    actor.start
    val result = actor !! "Hello"
    assertEquals("World", result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def testSendReceiveException = {
    implicit val timeout = 500000000L
    val actor = new RemoteActorSpecActorBidirectional
    actor.makeRemote(RemoteServer.HOSTNAME, RemoteServer.PORT)
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
