/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import akka.actor._
import RemoteTypedActorLog._

class ServerInitiatedRemoteTypedSessionActorSpec extends AkkaRemoteTest {

  override def beforeEach = {
    super.beforeEach

    remote.registerTypedPerSessionActor("typed-session-actor-service",
      TypedActor.newInstance(classOf[RemoteTypedSessionActor], classOf[RemoteTypedSessionActorImpl], 1000))
  }

  // make sure the servers shutdown cleanly after the test has finished
  override def afterEach() {
    super.afterEach()
    clearMessageLogs
  }

  "A remote session Actor" should {
    "create a new session actor per connection" in {

      val session1 = remote.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, host, port)

      session1.getUser() must equal("anonymous")
      session1.login("session[1]")
      session1.getUser() must equal("session[1]")

      remote.shutdownClientModule()
      Thread.sleep(1000)
      val session2 = remote.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, host, port)

      session2.getUser() must equal("anonymous")

    }

    "stop the actor when the client disconnects" in {
      val session1 = remote.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, host, port)

      session1.getUser() must equal("anonymous")

      RemoteTypedSessionActorImpl.getInstances() must have size (1)
      remote.shutdownClientModule()
      Thread.sleep(1000)
      RemoteTypedSessionActorImpl.getInstances() must have size (0)

    }

    "stop the actor when there is an error" in {
      val session1 = remote.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, host, port)

      session1.doSomethingFunny()

      remote.shutdownClientModule()
      Thread.sleep(1000)
      RemoteTypedSessionActorImpl.getInstances() must have size (0)
    }

    "be able to unregister" in {
      remote.registerTypedPerSessionActor("my-service-1", TypedActor.newInstance(classOf[RemoteTypedSessionActor], classOf[RemoteTypedSessionActorImpl], 1000))

      remote.typedActorsFactories.get("my-service-1") must not be (null)
      remote.unregisterTypedPerSessionActor("my-service-1")
      remote.typedActorsFactories.get("my-service-1") must be(null)
    }
  }
}

