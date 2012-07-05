/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import java.util.concurrent.TimeUnit

import akka.actor._
import RemoteTypedActorLog._

class ServerInitiatedRemoteTypedActorSpec extends AkkaRemoteTest {

  override def beforeEach = {
    super.beforeEach
    val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
    remote.registerTypedActor("typed-actor-service", typedActor)
  }

  override def afterEach {
    super.afterEach
    clearMessageLogs
  }

  def createRemoteActorRef = remote.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, host, port)

  "Server managed remote typed Actor " should {

    "receive one-way message" in {
      val actor = createRemoteActorRef
      actor.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal("oneway")
    }

    "should respond to request-reply message" in {
      val actor = createRemoteActorRef
      actor.requestReply("ping") must equal("pong")
    }

    "should not recreate registered actors" in {
      val actor = createRemoteActorRef
      val numberOfActorsInRegistry = Actor.registry.actors.length
      actor.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal("oneway")
      numberOfActorsInRegistry must be(Actor.registry.actors.length)
    }

    "should support multiple variants to get the actor from client side" in {
      var actor = createRemoteActorRef

      actor.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal("oneway")

      actor = remote.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", host, port)

      actor.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal("oneway")

      actor = remote.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, host, port, this.getClass().getClassLoader)

      actor.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal("oneway")
    }

    "should register and unregister typed actors" in {
      val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
      remote.registerTypedActor("my-test-service", typedActor)
      remote.typedActors.get("my-test-service") must not be (null)
      remote.unregisterTypedActor("my-test-service")
      remote.typedActors.get("my-test-service") must be(null)
    }

    "should register and unregister typed actors by uuid" in {
      val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
      val init = AspectInitRegistry.initFor(typedActor)
      val uuid = "uuid:" + init.actorRef.uuid

      remote.registerTypedActor(uuid, typedActor)
      remote.typedActorsByUuid.get(init.actorRef.uuid.toString) must not be (null)

      remote.unregisterTypedActor(uuid)
      remote.typedActorsByUuid.get(init.actorRef.uuid.toString) must be(null)
    }

    "should find typed actors by uuid" in {
      val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
      val init = AspectInitRegistry.initFor(typedActor)
      val uuid = "uuid:" + init.actorRef.uuid

      remote.registerTypedActor(uuid, typedActor)
      remote.typedActorsByUuid.get(init.actorRef.uuid.toString) must not be (null)

      val actor = remote.typedActorFor(classOf[RemoteTypedActorOne], uuid, host, port)
      actor.oneWay
      oneWayLog.poll(5, TimeUnit.SECONDS) must equal("oneway")
    }
  }
}

