/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import java.util.concurrent.TimeUnit

import akka.remote.{RemoteServer, RemoteClient}
import akka.actor._
import RemoteTypedActorLog._

object ServerInitiatedRemoteTypedActorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null
}

@RunWith(classOf[JUnitRunner])
class ServerInitiatedRemoteTypedActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {
  import ServerInitiatedRemoteTypedActorSpec._

  private val unit = TimeUnit.MILLISECONDS


  override def beforeAll = {
    server = new RemoteServer()
    server.start(HOSTNAME, PORT)

    val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
    server.registerTypedActor("typed-actor-service", typedActor)

    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  override def afterAll = {
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  describe("Server managed remote typed Actor ") {

    it("should receive one-way message") {
      clearMessageLogs
      val actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, HOSTNAME, PORT)
      expect("oneway") {
        actor.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }
    }

    it("should respond to request-reply message") {
      clearMessageLogs
      val actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, HOSTNAME, PORT)
      expect("pong") {
        actor.requestReply("ping")
      }
    }

    it("should not recreate registered actors") {
      val actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, HOSTNAME, PORT)
      val numberOfActorsInRegistry = ActorRegistry.actors.length
      expect("oneway") {
        actor.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }
      assert(numberOfActorsInRegistry === ActorRegistry.actors.length)
    }

    it("should support multiple variants to get the actor from client side") {
      var actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, HOSTNAME, PORT)
      expect("oneway") {
        actor.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }
      actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", HOSTNAME, PORT)
      expect("oneway") {
        actor.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }
      actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], "typed-actor-service", 5000L, HOSTNAME, PORT, this.getClass().getClassLoader)
      expect("oneway") {
        actor.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }
    }

    it("should register and unregister typed actors") {
      val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
      server.registerTypedActor("my-test-service", typedActor)
      assert(server.typedActors.get("my-test-service") ne null, "typed actor registered")
      server.unregisterTypedActor("my-test-service")
      assert(server.typedActors.get("my-test-service") eq null, "typed actor unregistered")
    }

    it("should register and unregister typed actors by uuid") {
      val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
      val init = AspectInitRegistry.initFor(typedActor)
      val uuid = "uuid:" + init.actorRef.uuid
      server.registerTypedActor(uuid, typedActor)
      assert(server.typedActorsByUuid.get(init.actorRef.uuid.toString) ne null, "typed actor registered")
      server.unregisterTypedActor(uuid)
      assert(server.typedActorsByUuid.get(init.actorRef.uuid.toString) eq null, "typed actor unregistered")
    }

    it("should find typed actors by uuid") {
      val typedActor = TypedActor.newInstance(classOf[RemoteTypedActorOne], classOf[RemoteTypedActorOneImpl], 1000)
      val init = AspectInitRegistry.initFor(typedActor)
      val uuid = "uuid:" + init.actorRef.uuid
      server.registerTypedActor(uuid, typedActor)
      assert(server.typedActorsByUuid.get(init.actorRef.uuid.toString) ne null, "typed actor registered")

      val actor = RemoteClient.typedActorFor(classOf[RemoteTypedActorOne], uuid, HOSTNAME, PORT)
      expect("oneway") {
        actor.oneWay
        oneWayLog.poll(5, TimeUnit.SECONDS)
      }

    }
  }
}

