package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.dispatch.Dispatchers

import ProtobufProtocol.ProtobufPOJO

/* ---------------------------
Uses this Protobuf message:

message ProtobufPOJO {
  required uint64 id = 1;
  required string name = 2;
  required bool status = 3;
}
--------------------------- */

object ProtobufActorMessageSerializationSpec {
  val unit = TimeUnit.MILLISECONDS
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null

  class RemoteActorSpecActorBidirectional extends Actor {
    start
    def receive = {
      case pojo: ProtobufPOJO =>
        val id = pojo.getId
        reply(id + 1)
      case msg =>
        throw new RuntimeException("Expected a ProtobufPOJO message but got: " + msg)
    }
  }
}

class ProtobufActorMessageSerializationSpec extends JUnitSuite {
  import ProtobufActorMessageSerializationSpec._

  @Before
  def init() {
    server = new RemoteServer
    server.start(HOSTNAME, PORT)
    server.register("RemoteActorSpecActorBidirectional", new RemoteActorSpecActorBidirectional)
    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  @After
  def finished() {
    server.shutdown
    RemoteClient.shutdownAll
    Thread.sleep(1000)
  }

  @Test
  def shouldSendReplyAsync = {
    val actor = RemoteClient.actorFor("RemoteActorSpecActorBidirectional", 5000L, HOSTNAME, PORT)
    val result = actor !! ProtobufPOJO.newBuilder
        .setId(11)
        .setStatus(true)
        .setName("Coltrane")
        .build
    assert(12L === result.get.asInstanceOf[Long])
    actor.stop
  }
}
                          