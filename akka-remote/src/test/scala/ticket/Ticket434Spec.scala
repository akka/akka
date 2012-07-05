/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.ticket

import akka.actor.Actor._
import akka.actor.{ Uuid, newUuid, uuidFrom }
import akka.actor.remote.ServerInitiatedRemoteActorSpec.RemoteActorSpecActorUnidirectional
import akka.remote.protocol.RemoteProtocol._
import akka.actor.remote.AkkaRemoteTest
import java.util.concurrent.CountDownLatch

class Ticket434Spec extends AkkaRemoteTest {
  "A server managed remote actor" should {
    "can use a custom service name containing ':'" in {
      val latch = new CountDownLatch(1)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register("my:service", actorOf[RemoteActorSpecActorUnidirectional])

      val actor = remote.actorFor("my:service", 5000L, host, port)

      actor ! "Ping"

      latch.await(1, unit) must be(true)
    }

    "should be possible to set the actor id and uuid" in {
      val uuid = newUuid
      val actorInfo = ActorInfoProtocol.newBuilder
        .setUuid(UuidProtocol.newBuilder.setHigh(uuid.getTime).setLow(uuid.getClockSeqAndNode).build)
        .setId("some-id")
        .setTarget("actorClassName")
        .setTimeout(5000L)
        .setActorType(ActorType.SCALA_ACTOR).build

      uuidFrom(actorInfo.getUuid.getHigh, actorInfo.getUuid.getLow) must equal(uuid)
      actorInfo.getId must equal("some-id")
    }
  }
}
