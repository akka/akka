package akka.actor.serialization

import java.util.concurrent.TimeUnit
import org.scalatest._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.actor.{ProtobufProtocol, Actor}
import ProtobufProtocol.ProtobufPOJO
import Actor._
import akka.remote.NettyRemoteSupport
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
      val actor = remote.actorFor("RemoteActorSpecActorBidirectional", 5000L, host, port)
      val result = actor !! ProtobufPOJO.newBuilder.setId(11).setStatus(true).setName("Coltrane").build
      result.as[Long].get must be (12)
    }
  }
}

