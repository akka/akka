package akka.actor.serialization

import java.util.concurrent.TimeUnit
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import akka.remote.{RemoteServer, RemoteClient}
import akka.actor.{ProtobufProtocol, Actor}
import ProtobufProtocol.ProtobufPOJO
import Actor._

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
    def receive = {
      case pojo: ProtobufPOJO =>
        val id = pojo.getId
        self.reply(id + 1)
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
    server.register("RemoteActorSpecActorBidirectional", actorOf[RemoteActorSpecActorBidirectional])
    Thread.sleep(1000)
  }

  // make sure the servers postStop cleanly after the test has finished
  @After
  def finished() {
    server.shutdown
    RemoteClient.shutdownAll
    Thread.sleep(1000)
  }

  @Test
  def shouldSendReplyAsync  {
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

