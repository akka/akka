/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.actor.ticket

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.remote.ServerInitiatedRemoteActorSpec.RemoteActorSpecActorUnidirectional
import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.remote.{RemoteClient, RemoteServer}
import se.scalablesolutions.akka.remote.protocol.RemoteProtocol._


class Ticket434Spec extends Spec with ShouldMatchers {

  describe("A server managed remote actor") {
    it("should possible be use a custom service name containing ':'") {
      val server = new RemoteServer().start("localhost", 9999)
      server.register("my:service", actorOf[RemoteActorSpecActorUnidirectional])

      val actor = RemoteClient.actorFor("my:service", 5000L, "localhost", 9999)
      actor ! "OneWay"

      assert(RemoteActorSpecActorUnidirectional.latch.await(1, TimeUnit.SECONDS))
      actor.stop

      server.shutdown
      RemoteClient.shutdownAll
    }
  }

  describe("The ActorInfoProtocol") {
    it("should be possible to set the acor id and uuuid") {
      val actorInfoBuilder = ActorInfoProtocol.newBuilder
        .setUuid("unique-id")
        .setId("some-id")
        .setTarget("actorClassName")
        .setTimeout(5000L)
        .setActorType(ActorType.SCALA_ACTOR)
      val actorInfo = actorInfoBuilder.build
      assert(actorInfo.getUuid === "unique-id")
      assert(actorInfo.getId === "some-id")
    }
  }
}
