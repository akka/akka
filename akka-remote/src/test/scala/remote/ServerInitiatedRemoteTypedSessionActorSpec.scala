/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor.remote

import org.scalatest._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import java.util.concurrent.TimeUnit

import akka.remote.{RemoteServer, RemoteClient}
import akka.actor._
import RemoteTypedActorLog._

object ServerInitiatedRemoteTypedSessionActorSpec {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null
}

@RunWith(classOf[JUnitRunner])
class ServerInitiatedRemoteTypedSessionActorSpec extends
  FlatSpec with
  ShouldMatchers with
  BeforeAndAfterEach  {
  import ServerInitiatedRemoteTypedActorSpec._

  private val unit = TimeUnit.MILLISECONDS


  override def beforeEach = {
    server = new RemoteServer()
    server.start(HOSTNAME, PORT)

    server.registerTypedPerSessionActor("typed-session-actor-service",
      TypedActor.newInstance(classOf[RemoteTypedSessionActor], classOf[RemoteTypedSessionActorImpl], 1000))

    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  override def afterEach = {
    try {
      server.shutdown
      RemoteClient.shutdownAll
      Thread.sleep(1000)
    } catch {
      case e => ()
    }
  }

  "A remote session Actor" should "create a new session actor per connection" in {
    clearMessageLogs

    val session1 = RemoteClient.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, HOSTNAME, PORT)

    session1.getUser() should equal ("anonymous")
    session1.login("session[1]")
    session1.getUser() should equal ("session[1]")

    RemoteClient.shutdownAll

    val session2 = RemoteClient.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, HOSTNAME, PORT)

    session2.getUser() should equal ("anonymous")

  }

  it should "stop the actor when the client disconnects" in {

    val session1 = RemoteClient.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, HOSTNAME, PORT)

    session1.getUser() should equal ("anonymous")

    RemoteTypedSessionActorImpl.getInstances() should have size (1)
    RemoteClient.shutdownAll
    Thread.sleep(1000)
    RemoteTypedSessionActorImpl.getInstances() should have size (0)

  }

  it should "stop the actor when there is an error" in {

    val session1 = RemoteClient.typedActorFor(classOf[RemoteTypedSessionActor], "typed-session-actor-service", 5000L, HOSTNAME, PORT)

    session1.doSomethingFunny()

    RemoteClient.shutdownAll
    Thread.sleep(1000)
    RemoteTypedSessionActorImpl.getInstances() should have size (0)

  }


  it should "be able to unregister" in {
    server.registerTypedPerSessionActor("my-service-1",TypedActor.newInstance(classOf[RemoteTypedSessionActor], classOf[RemoteTypedSessionActorImpl], 1000))

    server.typedActorsFactories.get("my-service-1") should not be (null)
    server.unregisterTypedPerSessionActor("my-service-1")
    server.typedActorsFactories.get("my-service-1") should be (null)
  }

}

