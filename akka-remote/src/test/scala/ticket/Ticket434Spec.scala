/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor.ticket

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import akka.actor.Actor._
import akka.actor.{Uuid,newUuid,uuidFrom}
import akka.actor.remote.ServerInitiatedRemoteActorSpec.RemoteActorSpecActorUnidirectional
import java.util.concurrent.TimeUnit
import akka.remote.{RemoteClient, RemoteServer}
import akka.remote.protocol.RemoteProtocol._


class Ticket434Spec extends Spec with ShouldMatchers {

  val HOSTNAME = "localhost"
  val PORT = 9991

  describe("A server managed remote actor") {
    it("can use a custom service name containing ':'") {
      val server = new RemoteServer().start(HOSTNAME, PORT)
      server.register("my:service", actorOf[RemoteActorSpecActorUnidirectional])

      val actor = RemoteClient.actorFor("my:service", 5000L, HOSTNAME, PORT)
      actor ! "OneWay"

      assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
      actor.stop

      server.shutdown
      RemoteClient.shutdownAll
    }
  }

  describe("The ActorInfoProtocol") {
    it("should be possible to set the acor id and uuuid") {
      val uuid = newUuid
      val actorInfoBuilder = ActorInfoProtocol.newBuilder
        .setUuid(UuidProtocol.newBuilder.setHigh(uuid.getTime).setLow(uuid.getClockSeqAndNode).build)
        .setId("some-id")
        .setTarget("actorClassName")
        .setTimeout(5000L)
        .setActorType(ActorType.SCALA_ACTOR)
      val actorInfo = actorInfoBuilder.build
      assert(uuidFrom(actorInfo.getUuid.getHigh,actorInfo.getUuid.getLow) === uuid)
      assert(actorInfo.getId === "some-id")
    }
  }
}
