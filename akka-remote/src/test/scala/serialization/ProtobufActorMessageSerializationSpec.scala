package akka.actor.serialization

import akka.actor.{ProtobufProtocol, Actor}
import ProtobufProtocol.ProtobufPOJO
import Actor._
import akka.actor.remote.AkkaRemoteTest

/* ---------------------------
Uses this Protobuf message:

message ProtobufPOJO {
  required uint64 id = 1;
  required string name = 2;
  required bool status = 3;
}
--------------------------- */

object ProtobufActorMessageSerializationSpec {
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

class ProtobufActorMessageSerializationSpec extends AkkaRemoteTest {
  import ProtobufActorMessageSerializationSpec._

  "A ProtobufMessage" should {
    "SendReplyAsync" in {
      remote.register("RemoteActorSpecActorBidirectional",actorOf[RemoteActorSpecActorBidirectional])
      val actor = remote.actorFor("RemoteActorSpecActorBidirectional", 5000L, host, port)
      val result = actor !! ProtobufPOJO.newBuilder.setId(11).setStatus(true).setName("Coltrane").build
      result.as[Long] must equal (Some(12))
    }
  }
}

